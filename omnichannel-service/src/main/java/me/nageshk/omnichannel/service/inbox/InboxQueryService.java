package me.nageshk.omnichannel.service.inbox;

import static org.springframework.data.mongodb.core.query.Criteria.where;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import me.nageshk.omnichannel.common.api.ForbiddenException;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.inbox.ChatStatus;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebChatAction;
import me.nageshk.omnichannel.db.inbox.WaWebChatRepository;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import me.nageshk.omnichannel.service.inbox.InboxScope.ListScope;
import me.nageshk.omnichannel.service.inbox.ThreadPage.ThreadItem;
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
    /** The longest search text used; the rest is ignored. */
    static final int MAX_SEARCH_LENGTH = 100;

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
     * One page of a number's chats, newest message first; a page never splits a group of chats sharing one
     * {@code lastMessageAt}, so the strict {@code before} cursor loses none.
     *
     * @param requested the list to show, or null for the widest the user may see
     * @param status OPEN or CLOSED, or null for both
     * @param phoneNumberIds the numbers to list, empty for every number the user may open
     * @param q case-insensitive prefix of the contact's name or phone
     * @param before only chats whose last message is older than this
     * @throws ForbiddenException if the user may not use the requested list
     */
    public ChatsPage chats(AuthenticatedUser me, String workspaceId, Collection<String> phoneNumberIds,
            ListScope requested, ChatStatus status, String q, boolean unreadOnly, Instant before, int limit) {
        InboxScope scope = scopes.scopeOf(me, workspaceId);
        ListScope list = requested != null ? requested : scopes.defaultScope(scope);
        if (!scope.allows(list)) throw new ForbiddenException("you may not use the " + list + " list");
        // the numbers asked for must all be ones the user may open; none asked for means all of them
        Set<String> visible = scopes.visibleNumbers(scope, workspaceId).stream().map(v -> v.number().getId())
                .collect(Collectors.toSet());
        Collection<String> selected = phoneNumberIds == null || phoneNumberIds.isEmpty() ? visible : phoneNumberIds;
        if (!visible.containsAll(selected)) throw new NotFoundException("phone number not found");

        List<Criteria> all = new ArrayList<>();
        all.add(where("workspaceId").is(workspaceId));
        all.add(where("phoneNumberId").in(List.copyOf(selected)));
        all.add(scope.criteriaFor(list));
        if (status != null) all.add(where("status").is(status));
        if (unreadOnly) all.add(where("unreadCount").gt(0));
        if (q != null && !q.isBlank()) {
            List<String> contactIds = matchingContacts(workspaceId, cut(q.strip()));
            if (contactIds.isEmpty()) return new ChatsPage(List.of(), false);
            all.add(where("contactId").in(contactIds));
        }
        List<Criteria> older = new ArrayList<>(all);
        if (before != null) older.add(where("lastMessageAt").lt(before));
        List<WaWebChat> found = mongo.find(newestFirst(older, "lastMessageAt").limit(limit + 1), WaWebChat.class);
        boolean hasMore = found.size() > limit;
        List<WaWebChat> page = hasMore ? new ArrayList<>(found.subList(0, limit)) : found;
        if (hasMore && sameInstant(found.get(limit).getLastMessageAt(), page.get(limit - 1).getLastMessageAt())) {
            Instant tie = page.get(limit - 1).getLastMessageAt();
            page.removeIf(c -> sameInstant(c.getLastMessageAt(), tie));
            page.addAll(mongo.find(newestFirst(with(all, where("lastMessageAt").is(tie)), "lastMessageAt"),
                    WaWebChat.class));
            hasMore = mongo.exists(new Query(new Criteria().andOperator(with(all, where("lastMessageAt").lt(tie)))),
                    WaWebChat.class);
        }
        return new ChatsPage(views.chats(page), hasMore);
    }

    /**
     * Messages and audit entries older than {@code before} merged by time, the newest {@code limit} oldest-first;
     * a page never splits a group of items sharing one instant, so the strict {@code before} cursor loses none.
     */
    public ThreadPage thread(WaWebChat chat, Instant before, int limit) {
        List<Criteria> base = List.of(where("chatId").is(chat.getId()));
        List<Row> merged = new ArrayList<>(rows(base, before, limit + 1));
        merged.sort(NEWEST_FIRST);
        boolean hasMore = merged.size() > limit;
        List<Row> page = hasMore ? new ArrayList<>(merged.subList(0, limit)) : merged;
        if (hasMore && sameInstant(merged.get(limit).at(), page.get(limit - 1).at())) {
            Instant tie = page.get(limit - 1).at();
            page.removeIf(r -> sameInstant(r.at(), tie));
            page.addAll(tied(base, tie));
            page.sort(NEWEST_FIRST);
            hasMore = mongo.exists(new Query(new Criteria().andOperator(with(base, where("timestamp").lt(tie)))),
                    WaWebMessage.class)
                    || mongo.exists(new Query(new Criteria().andOperator(with(base, where("at").lt(tie)))),
                            WaWebChatAction.class);
        }
        List<ThreadItem> items = new ArrayList<>(page.stream().map(Row::item).toList());
        Collections.reverse(items);
        return new ThreadPage(items, hasMore);
    }

    /** A thread entry with the id that orders entries of the same instant. */
    private record Row(Instant at, String id, ThreadItem item) {
    }

    private static final Comparator<Row> NEWEST_FIRST =
            Comparator.comparing(Row::at).thenComparing(Row::id).reversed();

    private List<Row> rows(List<Criteria> base, Instant before, int max) {
        List<Row> rows = new ArrayList<>();
        for (WaWebMessage m : mongo.find(newestFirst(olderThan(base, "timestamp", before), "timestamp").limit(max),
                WaWebMessage.class))
            rows.add(new Row(m.getTimestamp(), m.getId(), new ThreadItem("message", views.message(m), null, m.getTimestamp())));
        for (WaWebChatAction a : mongo.find(newestFirst(olderThan(base, "at", before), "at").limit(max),
                WaWebChatAction.class))
            rows.add(new Row(a.getAt(), a.getId(), new ThreadItem("action", null, views.action(a), a.getAt())));
        return rows;
    }

    private List<Row> tied(List<Criteria> base, Instant at) {
        List<Row> rows = new ArrayList<>();
        for (WaWebMessage m : mongo.find(newestFirst(with(base, where("timestamp").is(at)), "timestamp"),
                WaWebMessage.class))
            rows.add(new Row(m.getTimestamp(), m.getId(), new ThreadItem("message", views.message(m), null, m.getTimestamp())));
        for (WaWebChatAction a : mongo.find(newestFirst(with(base, where("at").is(at)), "at"), WaWebChatAction.class))
            rows.add(new Row(a.getAt(), a.getId(), new ThreadItem("action", null, views.action(a), a.getAt())));
        return rows;
    }

    private static List<Criteria> olderThan(List<Criteria> base, String key, Instant before) {
        return before == null ? base : with(base, where(key).lt(before));
    }

    private static List<Criteria> with(List<Criteria> base, Criteria extra) {
        List<Criteria> all = new ArrayList<>(base);
        all.add(extra);
        return all;
    }

    /** All the criteria, sorted by {@code key} then id, both newest first so the order is stable across ties. */
    private static Query newestFirst(List<Criteria> all, String key) {
        return new Query(new Criteria().andOperator(all))
                .with(Sort.by(Sort.Order.desc(key), Sort.Order.desc("_id")));
    }

    private static boolean sameInstant(Instant a, Instant b) {
        return Objects.equals(a, b);
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

    /** A longer search text only costs the regex engine time; no name or phone is that long a prefix anyway. */
    private static String cut(String q) {
        return q.codePointCount(0, q.length()) <= MAX_SEARCH_LENGTH ? q
                : q.substring(0, q.offsetByCodePoints(0, MAX_SEARCH_LENGTH));
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
