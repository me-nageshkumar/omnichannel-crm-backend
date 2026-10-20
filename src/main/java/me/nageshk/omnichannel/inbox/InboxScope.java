package me.nageshk.omnichannel.inbox;

import static org.springframework.data.mongodb.core.query.Criteria.where;

import java.util.List;
import java.util.Set;
import org.springframework.data.mongodb.core.query.Criteria;

/**
 * Which chats of a number a user may see in one workspace: everything ({@link Level#ALL}), own chats,
 * own teams' chats and the unassigned queue ({@link Level#TEAM}), or only own chats ({@link Level#MINE}).
 *
 * <p>{@code teamIds} is the user's own team memberships for level TEAM, and every team of the
 * workspace for level ALL (so the Team list scope has something to match); it is empty for MINE.
 */
public record InboxScope(Level level, String userId, Set<String> teamIds) {

    /** Widest first: a level may do everything the levels after it may. */
    public enum Level { ALL, TEAM, MINE }

    /** The list a console user asks for. */
    public enum ListScope { MINE, TEAM, UNASSIGNED, ALL }

    /** Matches the chats this scope may see; empty (matches everything) for ALL. */
    public Criteria criteria() {
        return switch (level) {
            case ALL -> new Criteria();
            case TEAM -> new Criteria().orOperator(
                    where("assignedUserId").is(userId), where("assignedTeamId").in(teamList()), unassigned());
            case MINE -> where("assignedUserId").is(userId);
        };
    }

    /** {@link #criteria()} applied in memory. */
    public boolean covers(WaWebChat chat) {
        return switch (level) {
            case ALL -> true;
            case TEAM -> userId.equals(chat.getAssignedUserId())
                    || (chat.getAssignedTeamId() != null && teamIds.contains(chat.getAssignedTeamId()))
                    || (chat.getAssignedUserId() == null && chat.getAssignedTeamId() == null);
            case MINE -> userId.equals(chat.getAssignedUserId());
        };
    }

    /** Whether the user may ask for this list: Mine always, Team and Unassigned from level TEAM, All only at ALL. */
    public boolean allows(ListScope requested) {
        return switch (requested) {
            case MINE -> true;
            case TEAM, UNASSIGNED -> level.compareTo(Level.TEAM) <= 0;
            case ALL -> level == Level.ALL;
        };
    }

    /** The chats of one requested list; check {@link #allows} first. */
    public Criteria criteriaFor(ListScope requested) {
        return switch (requested) {
            case MINE -> where("assignedUserId").is(userId);
            case TEAM -> new Criteria().orOperator(where("assignedTeamId").in(teamList()), unassigned());
            case UNASSIGNED -> unassigned();
            case ALL -> new Criteria();
        };
    }

    private static Criteria unassigned() {
        return new Criteria().andOperator(where("assignedUserId").is(null), where("assignedTeamId").is(null));
    }

    private List<String> teamList() {
        return List.copyOf(teamIds);
    }
}
