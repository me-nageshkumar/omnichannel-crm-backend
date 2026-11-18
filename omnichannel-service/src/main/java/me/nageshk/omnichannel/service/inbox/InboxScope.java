package me.nageshk.omnichannel.service.inbox;

import static org.springframework.data.mongodb.core.query.Criteria.where;

import java.util.List;
import java.util.Set;
import me.nageshk.omnichannel.db.inbox.ChatStatus;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import org.springframework.data.mongodb.core.query.Criteria;

/**
 * Which chats of a workspace a user may see (design §3.5): everything ({@link Level#ALL}); own chats, own teams'
 * chats and the queues on numbers own teams own ({@link Level#TEAM}); own chats and those queues only
 * ({@link Level#QUEUE}); or only own chats ({@link Level#MINE}).
 *
 * <p>{@code teamIds} is the user's own team memberships (every team of the workspace for ALL) and
 * {@code ownedNumberIds} the numbers those teams own (every number for ALL). A chat nobody holds counts as a team's
 * queue only on a number that team owns; a chat routed to one of the user's teams is theirs wherever it is (the
 * hand-off case, §3.6).
 *
 * <p>A chat a chatbot holds ({@link ChatStatus#BOT}) is in none of those: only {@code bots} (the user holds
 * {@code inbox:view-bot}) sees it, and only on an owned number (spec 2026-11-18 §5.6).
 */
public record InboxScope(Level level, String userId, Set<String> teamIds, Set<String> ownedNumberIds, boolean bots) {

    private static final String BOT = ChatStatus.BOT.name();

    /** Widest first: a level may do everything the levels after it may. */
    public enum Level { ALL, TEAM, QUEUE, MINE }

    /** The list a console user asks for; {@code BOT} is the chats chatbots hold. */
    public enum ListScope { MINE, TEAM, UNASSIGNED, ALL, BOT }

    /** A scope without the chatbot list. */
    public InboxScope(Level level, String userId, Set<String> teamIds, Set<String> ownedNumberIds) {
        this(level, userId, teamIds, ownedNumberIds, false);
    }

    /** Matches the chats this scope may see. */
    public Criteria criteria() {
        Criteria people = notBot(peopleCriteria());
        return bots ? new Criteria().orOperator(people, botChats()) : people;
    }

    /** The chats people hold or wait for; empty (matches everything) for ALL. */
    private Criteria peopleCriteria() {
        return switch (level) {
            case ALL -> new Criteria();
            case TEAM -> new Criteria().orOperator(
                    where("assignedUserId").is(userId), where("assignedTeamId").in(teamList()), ownedQueue());
            case QUEUE -> new Criteria().orOperator(
                    where("assignedUserId").is(userId), teamQueue(), ownedQueue());
            case MINE -> where("assignedUserId").is(userId);
        };
    }

    /** {@link #criteria()} applied in memory. */
    public boolean covers(WaWebChat chat) {
        if (chat.getStatus() == ChatStatus.BOT) {
            return bots && chat.getPhoneNumberId() != null && ownedNumberIds.contains(chat.getPhoneNumberId());
        }
        boolean own = userId.equals(chat.getAssignedUserId());
        boolean ownTeam = chat.getAssignedTeamId() != null && teamIds.contains(chat.getAssignedTeamId());
        boolean nobodys = chat.getAssignedUserId() == null && chat.getAssignedTeamId() == null;
        boolean ownedQueue = nobodys && chat.getPhoneNumberId() != null && ownedNumberIds.contains(chat.getPhoneNumberId());
        return switch (level) {
            case ALL -> true;
            case TEAM -> own || ownTeam || ownedQueue;
            case QUEUE -> own || (chat.getAssignedUserId() == null && ownTeam) || ownedQueue;
            case MINE -> own;
        };
    }

    /** Whether the user may ask for this list: Mine always, Unassigned from QUEUE, Team from TEAM, All only at ALL. */
    public boolean allows(ListScope requested) {
        return switch (requested) {
            case MINE -> true;
            case UNASSIGNED -> level.compareTo(Level.QUEUE) <= 0;
            case TEAM -> level.compareTo(Level.TEAM) <= 0;
            case ALL -> level == Level.ALL;
            case BOT -> bots;
        };
    }

    /** The chats of one requested list; check {@link #allows} first. */
    public Criteria criteriaFor(ListScope requested) {
        if (requested == ListScope.BOT) return botChats();
        return notBot(switch (requested) {
            case MINE -> where("assignedUserId").is(userId);
            case TEAM -> new Criteria().orOperator(where("assignedTeamId").in(teamList()), ownedQueue());
            case UNASSIGNED -> new Criteria().andOperator(where("assignedUserId").is(null),
                    new Criteria().orOperator(where("assignedTeamId").in(teamList()),
                            new Criteria().andOperator(where("assignedTeamId").is(null),
                                    where("phoneNumberId").in(numberList()))));
            case ALL, BOT -> new Criteria();
        });
    }

    /** Held by a chatbot, on a number one of own teams owns (every number at ALL). */
    private Criteria botChats() {
        return new Criteria().andOperator(where("status").is(BOT), where("phoneNumberId").in(numberList()));
    }

    /** {@code c} without the chats a chatbot holds. */
    private static Criteria notBot(Criteria c) {
        Criteria notBot = where("status").ne(BOT);
        return c.getCriteriaObject().isEmpty() ? notBot : new Criteria().andOperator(c, notBot);
    }

    /** Routed to one of own teams and not yet taken by anyone. */
    private Criteria teamQueue() {
        return new Criteria().andOperator(where("assignedUserId").is(null), where("assignedTeamId").in(teamList()));
    }

    /** Held by nobody, on a number one of own teams owns. */
    private Criteria ownedQueue() {
        return new Criteria().andOperator(where("assignedUserId").is(null), where("assignedTeamId").is(null),
                where("phoneNumberId").in(numberList()));
    }

    private List<String> teamList() {
        return List.copyOf(teamIds);
    }

    private List<String> numberList() {
        return List.copyOf(ownedNumberIds);
    }
}
