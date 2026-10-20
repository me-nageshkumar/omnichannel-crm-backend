package me.nageshk.omnichannel.inbox;

import static org.springframework.data.mongodb.core.query.Criteria.where;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import me.nageshk.omnichannel.api.ForbiddenException;
import me.nageshk.omnichannel.api.NotFoundException;
import me.nageshk.omnichannel.auth.AuthenticatedUser;
import me.nageshk.omnichannel.contact.Contact;
import me.nageshk.omnichannel.inbox.InboxScope.ListScope;
import me.nageshk.omnichannel.inbox.ThreadPage.ThreadItem;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/** Reads of the inbox: the chat list, one chat, its thread, always limited to what the user may see. */
@Service
@RequiredArgsConstructor
public class InboxQueryService {

    /** Contacts a search term may match before the chat list is cut off. */
    static final int MAX_SEARCH_CONTACTS = 200;

    private final MongoTemplate mongo;
    private final WaWebChatRepository chats;
    private final InboxScopeService scopes;
    private final InboxViews views;
    private final ApplicationEventPublisher publisher;

    /** @throws NotFoundException unless the chat is in the workspace and inside the user's scope */
    public WaWebChat requireVisible(AuthenticatedUser me, String workspaceId, String chatId) {
        InboxScope scope = scopes.scopeOf(me, workspaceId);
        return chats.findByIdAndWorkspaceId(chatId, workspaceId).filter(scope::covers)
                .orElseThrow(() -> new NotFoundException("chat not found"));
    }

    /**
     * One page of a number's chats, newest message first.
     *
     * @param requested the list to show, or null for the widest the user may see
     * @param status OPEN or CLOSED, or null for both
     * @param q case-insensitive prefix of the contact's name or phone
     * @param before only chats whose last message is older than this
     * @throws ForbiddenException if the user may not use the requested list
     */
    public ChatsPage chats(AuthenticatedUser me, String workspaceId, String phoneNumberId, ListScope requested,
            ChatStatus status, String q, boolean unreadOnly, Instant before, int limit) {
        InboxScope scope = scopes.scopeOf(me, workspaceId);
        ListScope list = requested != null ? requested : scopes.defaultScope(scope);
        if (!scope.allows(list)) throw new ForbiddenException("you may not use the " + list + " list");

        List<Criteria> all = new ArrayList<>();
        all.add(where("workspaceId").is(workspaceId));
        all.add(where("phoneNumberId").is(phoneNumberId));
        all.add(scope.criteriaFor(list));
        if (status != null) all.add(where("status").is(status));
        if (unreadOnly) all.add(where("unreadCount").gt(0));
        if (before != null) all.add(where("lastMessageAt").lt(before));
        if (q != null && !q.isBlank()) {
            List<String> contactIds = matchingContacts(workspaceId, q.strip());
            if (contactIds.isEmpty()) return new ChatsPage(List.of(), false);
            all.add(where("contactId").in(contactIds));
        }
        Query query = new Query(new Criteria().andOperator(all)).with(Sort.by(Sort.Direction.DESC, "lastMessageAt"))
                .limit(limit + 1);
        List<WaWebChat> found = mongo.find(query, WaWebChat.class);
        boolean hasMore = found.size() > limit;
        return new ChatsPage(views.chats(hasMore ? found.subList(0, limit) : found), hasMore);
    }

    /** Messages and audit entries older than {@code before} merged by time, the newest {@code limit} oldest-first. */
    public ThreadPage thread(WaWebChat chat, Instant before, int limit) {
        Query messageQuery = new Query(timeBefore(chat, "timestamp", before))
                .with(Sort.by(Sort.Direction.DESC, "timestamp")).limit(limit + 1);
        Query actionQuery = new Query(timeBefore(chat, "at", before))
                .with(Sort.by(Sort.Direction.DESC, "at")).limit(limit + 1);
        List<ThreadItem> merged = new ArrayList<>();
        for (WaWebMessage m : mongo.find(messageQuery, WaWebMessage.class))
            merged.add(new ThreadItem("message", views.message(m), null, m.getTimestamp()));
        for (WaWebChatAction a : mongo.find(actionQuery, WaWebChatAction.class))
            merged.add(new ThreadItem("action", null, views.action(a), a.getAt()));
        merged.sort(Comparator.comparing(ThreadItem::at).reversed());
        boolean hasMore = merged.size() > limit;
        List<ThreadItem> page = new ArrayList<>(hasMore ? merged.subList(0, limit) : merged);
        Collections.reverse(page);
        return new ThreadPage(page, hasMore);
    }

    /**
     * Zeroes the unread count and bumps the version, so an inbound message being stored at the same moment
     * retries instead of overwriting this, then tells the open consoles ({@link InboxEvents.ChatChanged}).
     *
     * @return the chat as it now stands
     * @throws NotFoundException if the chat was deleted meanwhile
     */
    public WaWebChat markRead(WaWebChat chat) {
        if (chat.getUnreadCount() == 0) return chat;
        WaWebChat updated = mongo.findAndModify(new Query(where("_id").is(chat.getId())),
                new Update().set("unreadCount", 0).inc("version", 1), FindAndModifyOptions.options().returnNew(true),
                WaWebChat.class);
        if (updated == null) throw new NotFoundException("chat not found");
        publisher.publishEvent(new InboxEvents.ChatChanged(updated));
        return updated;
    }

    private static Criteria timeBefore(WaWebChat chat, String field, Instant before) {
        Criteria c = where("chatId").is(chat.getId());
        return before == null ? c : c.and(field).lt(before);
    }

    private List<String> matchingContacts(String workspaceId, String q) {
        String prefix = "^" + escape(q);
        Query query = new Query(new Criteria().andOperator(where("workspaceId").is(workspaceId),
                new Criteria().orOperator(where("name").regex(prefix, "i"), where("phone").regex(prefix, "i"))))
                .limit(MAX_SEARCH_CONTACTS);
        query.fields().include("_id");
        return mongo.find(query, Contact.class).stream().map(Contact::getId).toList();
    }

    /** Makes every character of {@code s} literal in a regular expression. */
    static String escape(String s) {
        StringBuilder out = new StringBuilder();
        s.codePoints().forEach(c -> {
            if (c < 128 && !Character.isLetterOrDigit(c) && c != ' ') out.append('\\');
            out.appendCodePoint(c);
        });
        return out.toString();
    }
}
