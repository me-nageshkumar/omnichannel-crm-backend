package me.nageshk.omnichannel.service.inbox.history;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactRepository;
import me.nageshk.omnichannel.db.inbox.HistoryChatStatus;
import me.nageshk.omnichannel.db.inbox.HistoryImportStatus;
import me.nageshk.omnichannel.db.inbox.HistoryScopeKind;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebChatRepository;
import me.nageshk.omnichannel.db.inbox.WaWebHistoryImport;
import me.nageshk.omnichannel.db.inbox.WaWebHistoryImportChat;
import me.nageshk.omnichannel.db.inbox.WaWebHistoryImportChatRepository;
import me.nageshk.omnichannel.db.inbox.WaWebHistoryImportRepository;
import me.nageshk.omnichannel.db.phone.ConnectionType;
import me.nageshk.omnichannel.db.phone.PhoneNumber;
import me.nageshk.omnichannel.db.phone.PhoneNumberRepository;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import me.nageshk.omnichannel.service.contact.Phones;
import org.bson.types.ObjectId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/** Creates, cancels and reads chat history imports. */
@Service
public class HistoryImportService {

    static final int PAGE_SIZE = 50;
    static final int MAX_CHATS = 500;

    private final WaWebHistoryImportRepository imports;
    private final WaWebHistoryImportChatRepository items;
    private final PhoneNumberRepository phoneNumbers;
    private final ContactRepository contacts;
    private final WaWebChatRepository chats;
    private final HistoryImportViews views;
    private final MongoTemplate mongo;

    HistoryImportService(WaWebHistoryImportRepository imports, WaWebHistoryImportChatRepository items,
            PhoneNumberRepository phoneNumbers, ContactRepository contacts, WaWebChatRepository chats,
            HistoryImportViews views, MongoTemplate mongo) {
        this.imports = imports;
        this.items = items;
        this.phoneNumbers = phoneNumbers;
        this.contacts = contacts;
        this.chats = chats;
        this.views = views;
        this.mongo = mongo;
    }

    public record Created(WaWebHistoryImport imp, boolean created) {
    }

    public record ImportPage(List<HistoryImportView> items, long total, int page) {
    }

    public record ChatPage(List<HistoryChatView> items, long total, int page) {
    }

    public Created create(AuthenticatedUser me, String workspaceId, String phoneNumberId, HistoryImportRequest req) {
        PhoneNumber number = number(workspaceId, phoneNumberId);
        checkRange(req.from(), req.to());
        HistoryImportRequest.ScopeRequest scope = req.scope();
        if (scope.kind() == HistoryScopeKind.ALL) {
            return create(me, number, HistoryScopeKind.ALL, List.of(), List.of(), Map.of(), req.from(), req.to());
        }
        TreeSet<String> remoteIds = new TreeSet<>();
        TreeSet<String> phones = new TreeSet<>();
        Map<String, String> requestedPhones = new HashMap<>();
        for (String raw : scope.phones() == null ? List.<String>of() : scope.phones()) {
            String phone = Phones.normalise(raw);
            if (!Phones.isValid(phone)) throw new ValidationException("phones", Phones.MESSAGE);
            addPhone(phone, phones, remoteIds, requestedPhones);
            contacts.findByWorkspaceIdAndPhone(workspaceId, phone).map(Contact::getWaId)
                    .ifPresent(w -> remoteIds.add(remoteId(w)));
        }
        for (String id : scope.contactIds() == null ? List.<String>of() : scope.contactIds()) {
            Contact c = contacts.findByIdAndWorkspaceId(id, workspaceId)
                    .orElseThrow(() -> new ValidationException("contactIds", "unknown contact"));
            if (c.getPhone() != null) addPhone(c.getPhone(), phones, remoteIds, requestedPhones);
            if (c.getWaId() != null) remoteIds.add(remoteId(c.getWaId()));
        }
        if (remoteIds.isEmpty()) throw new ValidationException("scope", "choose at least one contact");
        if (remoteIds.size() > MAX_CHATS) throw new ValidationException("scope", "at most 500 chats per import");
        return create(me, number, HistoryScopeKind.CHATS, new ArrayList<>(remoteIds), new ArrayList<>(phones),
                requestedPhones, req.from(), req.to());
    }

    public Created createForChat(AuthenticatedUser me, String workspaceId, String chatId, Instant from, Instant to) {
        WaWebChat chat = chats.findByIdAndWorkspaceId(chatId, workspaceId)
                .orElseThrow(() -> new NotFoundException("chat not found"));
        checkRange(from, to);
        PhoneNumber number = number(workspaceId, chat.getPhoneNumberId());
        return create(me, number, HistoryScopeKind.CHATS, List.of(chat.getRemoteId()), List.of(), Map.of(), from, to);
    }

    public WaWebHistoryImport cancel(String workspaceId, String id) {
        WaWebHistoryImport imp = require(workspaceId, id);
        if (imp.getStatus() != HistoryImportStatus.QUEUED && imp.getStatus() != HistoryImportStatus.RUNNING) {
            throw HistoryImportErrors.state("cancelled");
        }
        long changed = mongo.updateFirst(new Query(Criteria.where("_id").is(id).and("status")
                .in(HistoryImportStatus.QUEUED, HistoryImportStatus.RUNNING)),
                new Update().set("status", HistoryImportStatus.CANCELLED).set("active", false)
                        .set("finishedAt", Instant.now()).set("waiting", null),
                WaWebHistoryImport.class).getModifiedCount();
        if (changed == 0) throw HistoryImportErrors.state("cancelled");
        mongo.updateMulti(new Query(Criteria.where("importId").is(id).and("status").is(HistoryChatStatus.RUNNING)),
                new Update().set("status", HistoryChatStatus.PENDING), WaWebHistoryImportChat.class);
        return require(workspaceId, id);
    }

    public WaWebHistoryImport require(String workspaceId, String id) {
        return imports.findByIdAndWorkspaceId(id, workspaceId)
                .orElseThrow(() -> new NotFoundException("history import not found"));
    }

    public ImportPage list(String workspaceId, String phoneNumberId, HistoryImportStatus status, int page) {
        Criteria where = Criteria.where("workspaceId").is(workspaceId);
        if (phoneNumberId != null) where.and("phoneNumberId").is(phoneNumberId);
        if (status != null) where.and("status").is(status);
        long total = mongo.count(new Query(where), WaWebHistoryImport.class);
        List<HistoryImportView> rows = mongo.find(paged(new Query(where), page)
                .with(Sort.by(Sort.Order.desc("creationTime"), Sort.Order.desc("_id"))), WaWebHistoryImport.class)
                .stream().map(views::view).toList();
        return new ImportPage(rows, total, page);
    }

    public ChatPage chats(String workspaceId, String id, HistoryChatStatus status, int page) {
        require(workspaceId, id);
        Criteria where = Criteria.where("importId").is(id);
        if (status != null) where.and("status").is(status);
        long total = mongo.count(new Query(where), WaWebHistoryImportChat.class);
        List<WaWebHistoryImportChat> rows = mongo.find(paged(new Query(where), page).with(Sort.by("_id")),
                WaWebHistoryImportChat.class);
        return new ChatPage(views.chats(rows), total, page);
    }

    private Created create(AuthenticatedUser me, PhoneNumber number, HistoryScopeKind kind, List<String> remoteIds,
            List<String> phones, Map<String, String> requestedPhones, Instant from, Instant to) {
        String scopeKey = sha256Hex(number.getId() + "|" + kind + "|" + String.join(",", remoteIds) + "|" + from + "|"
                + to);
        var existing = imports.findByPhoneNumberIdAndScopeKeyAndActiveTrue(number.getId(), scopeKey);
        if (existing.isPresent()) return new Created(existing.get(), false);
        WaWebHistoryImport imp = new WaWebHistoryImport();
        imp.setCustomerId(number.getCustomerId());
        imp.setWorkspaceId(number.getWorkspaceId());
        imp.setPhoneNumberId(number.getId());
        imp.setScope(new WaWebHistoryImport.Scope(kind, remoteIds, phones));
        imp.setRange(new WaWebHistoryImport.Range(from, to));
        imp.setScopeKey(scopeKey);
        imp.setActive(true);
        imp.setStatus(HistoryImportStatus.QUEUED);
        imp.setCounts(new WaWebHistoryImport.Counts(remoteIds.size(), 0, 0, 0, 0, 0, 0));
        imp.setRequestedByUserId(me.userId());
        imp.setRequestedByName(me.getUsername());
        imp.setId(new ObjectId().toHexString());
        if (kind == HistoryScopeKind.CHATS) insertItems(imp, number, remoteIds, requestedPhones);
        try {
            imp = imports.insert(imp);
        } catch (DuplicateKeyException e) {
            items.deleteByImportId(imp.getId());
            return new Created(imports.findByPhoneNumberIdAndScopeKeyAndActiveTrue(number.getId(), scopeKey)
                    .orElseThrow(() -> e), false);
        }
        return new Created(imp, true);
    }

    /** The items go in before the import, so a runner never sees a CHATS import without them. */
    private void insertItems(WaWebHistoryImport imp, PhoneNumber number, List<String> remoteIds,
            Map<String, String> requestedPhones) {
        List<WaWebHistoryImportChat> rows = new ArrayList<>();
        for (String remoteId : remoteIds) {
            WaWebHistoryImportChat row = new WaWebHistoryImportChat();
            row.setImportId(imp.getId());
            row.setWorkspaceId(number.getWorkspaceId());
            row.setPhoneNumberId(number.getId());
            row.setRemoteId(remoteId);
            row.setRequestedPhone(requestedPhones.get(remoteId));
            row.setStatus(HistoryChatStatus.PENDING);
            row.setCounts(WaWebHistoryImportChat.Counts.ZERO);
            rows.add(row);
        }
        items.insert(rows);
    }

    /** A typed or contact phone: its {@code @c.us} chat, plus the contact's own chat id when it has one. */
    private void addPhone(String phone, TreeSet<String> phones, TreeSet<String> remoteIds,
            Map<String, String> requestedPhones) {
        String normalised = Phones.normalise(phone);
        phones.add(normalised);
        String remoteId = normalised + "@c.us";
        remoteIds.add(remoteId);
        requestedPhones.put(remoteId, normalised);
    }

    private static String remoteId(String waId) {
        return waId.contains("@") ? waId : waId + "@c.us";
    }

    /** The number to import from: one of the workspace's, connected through WhatsApp Web and not disabled. */
    private PhoneNumber number(String workspaceId, String phoneNumberId) {
        PhoneNumber number = phoneNumbers.findByIdAndWorkspaceId(phoneNumberId, workspaceId)
                .orElseThrow(() -> new NotFoundException("phone number not found"));
        // a Cloud API number has no phone to read history from, and a disabled one never gets a device
        if (number.getConnectionType() != ConnectionType.WHATSAPP_WEB) {
            throw new ValidationException("phoneNumberId", "only WhatsApp Web numbers can import history");
        }
        if (number.getDisabled() != null) throw new ValidationException("phoneNumberId", "the number is disabled");
        return number;
    }

    private static void checkRange(Instant from, Instant to) {
        if (from != null && to != null && from.isAfter(to)) throw new ValidationException("from", "must not be after to");
    }

    private static String sha256Hex(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Query paged(Query q, int page) {
        return q.skip((long) Math.max(page, 0) * PAGE_SIZE).limit(PAGE_SIZE);
    }
}
