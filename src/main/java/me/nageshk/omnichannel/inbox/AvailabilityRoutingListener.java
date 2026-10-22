package me.nageshk.omnichannel.inbox;

import java.util.List;
import me.nageshk.omnichannel.tenant.Team;
import me.nageshk.omnichannel.tenant.TeamRepository;
import me.nageshk.omnichannel.user.Availability;
import me.nageshk.omnichannel.user.UserEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * When an agent comes back from Away, the chats waiting in their teams' queues under a round-robin setting are
 * routed again, oldest first, over every available member (design §3.2): the returning agent is not handed the
 * whole backlog, and team-only settings keep their queue for members to claim.
 */
@Component
public class AvailabilityRoutingListener {

    private static final Logger log = LoggerFactory.getLogger(AvailabilityRoutingListener.class);

    private final TeamRepository teams;
    private final InboxSettingService settings;
    private final WaWebChatRepository chats;
    private final ChatRoutingService routing;
    private final ApplicationEventPublisher publisher;

    public AvailabilityRoutingListener(TeamRepository teams, InboxSettingService settings, WaWebChatRepository chats,
            ChatRoutingService routing, ApplicationEventPublisher publisher) {
        this.teams = teams;
        this.settings = settings;
        this.chats = chats;
        this.routing = routing;
        this.publisher = publisher;
    }

    @EventListener
    public void on(UserEvents.AvailabilityChanged e) {
        if (e.previous() != Availability.Status.AWAY
                || e.user().availabilityStatus() != Availability.Status.AVAILABLE) {
            return;
        }
        for (Team team : teams.findByMemberIdsContaining(e.user().getId())) {
            InboxSetting setting = settings.effective(team.getWorkspaceId(), team.getId());
            if (setting.getRouting().mode() != RoutingMode.ROUND_ROBIN) continue;
            List<WaWebChat> queued = chats.findByWorkspaceIdAndAssignedTeamIdAndAssignedUserIdIsNullAndStatusOrderByLastMessageAtAsc(
                    team.getWorkspaceId(), team.getId(), ChatStatus.OPEN);
            for (WaWebChat chat : queued) {
                try {
                    // the team is re-read each time so the rotation sees the cursor the previous chat moved
                    Team fresh = teams.findById(team.getId()).orElse(team);
                    WaWebChat routed = routing.route(chat, fresh, ChatRoutingService.RoutingOptions.DEFAULT);
                    if (routed.getAssignedUserId() != null) publisher.publishEvent(new InboxEvents.ChatChanged(routed));
                } catch (RuntimeException ex) {
                    log.warn("re-routing queued chat {} after {} became available failed", chat.getId(),
                            e.user().getUsername(), ex);
                }
            }
        }
    }
}
