package me.nageshk.omnichannel.service.inbox;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.db.inbox.ActionType;
import me.nageshk.omnichannel.db.inbox.ChatStatus;
import me.nageshk.omnichannel.db.inbox.InboxSettingsMigration;
import me.nageshk.omnichannel.db.inbox.RoutedReason;
import me.nageshk.omnichannel.db.inbox.RoutingMode;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebChatAction;
import me.nageshk.omnichannel.db.inbox.WaWebChatActionRepository;
import me.nageshk.omnichannel.db.inbox.WaWebChatRepository;
import me.nageshk.omnichannel.db.phone.PhoneNumber;
import me.nageshk.omnichannel.db.phone.PhoneNumberRepository;
import me.nageshk.omnichannel.db.tenant.Team;
import me.nageshk.omnichannel.db.tenant.TeamRepository;
import me.nageshk.omnichannel.db.user.User;
import me.nageshk.omnichannel.db.user.UserRepository;
import me.nageshk.omnichannel.security.access.AccessService;
import me.nageshk.omnichannel.security.access.Authority;
import me.nageshk.omnichannel.service.phone.PhoneNumberEvents;
import me.nageshk.omnichannel.service.tenant.TeamEvents;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Keeps every open chat with someone who can open it (design §3.1): when a number changes owner, chats held by
 * people who can no longer see it (and the old team's queue) are routed again under the new team, or unassigned
 * when there is none; when members leave a team, their open chats on the team's numbers are routed again without
 * them. Also seeds a team's routing override from a number's pre-migration setting the first time that team becomes
 * the number's owner (§3.4).
 */
@Component
public class OwnershipRoutingListener {

    private static final Logger log = LoggerFactory.getLogger(OwnershipRoutingListener.class);

    private final WaWebChatRepository chats;
    private final WaWebChatActionRepository actions;
    private final TeamRepository teams;
    private final PhoneNumberRepository phoneNumbers;
    private final UserRepository users;
    private final AccessService access;
    private final InboxSettingService settings;
    private final InboxSettingsMigration migration;
    private final ChatRoutingService routing;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    public OwnershipRoutingListener(WaWebChatRepository chats, WaWebChatActionRepository actions,
            TeamRepository teams, PhoneNumberRepository phoneNumbers, UserRepository users, AccessService access,
            InboxSettingService settings, InboxSettingsMigration migration, ChatRoutingService routing,
            ApplicationEventPublisher publisher, Clock clock) {
        this.chats = chats;
        this.actions = actions;
        this.teams = teams;
        this.phoneNumbers = phoneNumbers;
        this.users = users;
        this.access = access;
        this.settings = settings;
        this.migration = migration;
        this.routing = routing;
        this.publisher = publisher;
        this.clock = clock;
    }

    @EventListener
    public void on(PhoneNumberEvents.TeamChanged e) {
        PhoneNumber number = e.number();
        Team newTeam = number.getTeamId() == null ? null
                : teams.findByIdAndWorkspaceId(number.getTeamId(), number.getWorkspaceId()).orElse(null);
        if (newTeam != null) seedOverrideFromLegacy(number, newTeam);
        for (WaWebChat chat : chats.findByPhoneNumberIdAndStatus(number.getId(), ChatStatus.OPEN)) {
            try {
                boolean oldQueue = chat.getAssignedUserId() == null
                        && Objects.equals(chat.getAssignedTeamId(), e.previousTeamId());
                boolean holderCanStay = chat.getAssignedUserId() != null && canSee(chat.getAssignedUserId(), newTeam,
                        number.getWorkspaceId());
                if (!oldQueue && (chat.getAssignedUserId() == null || holderCanStay)) continue;
                chat.setAssignedUserId(null);
                if (newTeam == null) {
                    chat.setAssignedTeamId(null);
                    unassign(chat);
                } else {
                    reroute(chat, newTeam, new ChatRoutingService.RoutingOptions(null, true, RoutedReason.OWNER_CHANGED));
                }
            } catch (RuntimeException ex) {
                log.warn("re-routing chat {} after number {} changed team failed", chat.getId(), number.getId(), ex);
            }
        }
    }

    @EventListener
    public void on(TeamEvents.MembersRemoved e) {
        Team team = e.team();
        List<PhoneNumber> owned = phoneNumbers.findByWorkspaceIdAndTeamIdIn(team.getWorkspaceId(), List.of(team.getId()));
        for (PhoneNumber number : owned) {
            for (WaWebChat chat : chats.findByPhoneNumberIdAndStatus(number.getId(), ChatStatus.OPEN)) {
                if (chat.getAssignedUserId() == null || !e.removedUserIds().contains(chat.getAssignedUserId())) continue;
                try {
                    String removed = chat.getAssignedUserId();
                    chat.setAssignedUserId(null);
                    chat.setAssignedTeamId(team.getId());
                    reroute(chat, team, new ChatRoutingService.RoutingOptions(removed, true, RoutedReason.MEMBER_REMOVED));
                } catch (RuntimeException ex) {
                    log.warn("re-routing chat {} after a member left team {} failed", chat.getId(), team.getId(), ex);
                }
            }
        }
    }

    /** Routes under {@code team}, or just unassigns when the team's routing is off; tells the open consoles. */
    private void reroute(WaWebChat chat, Team team, ChatRoutingService.RoutingOptions options) {
        if (settings.effective(chat.getWorkspaceId(), team.getId()).getRouting().mode() == RoutingMode.NONE) {
            chat.setAssignedTeamId(team.getId());
            unassign(chat);
            return;
        }
        WaWebChat routed = routing.route(chat, team, options);
        publisher.publishEvent(new InboxEvents.ChatChanged(routed));
    }

    /** Saves the cleared assignment and records that the system did it. */
    private void unassign(WaWebChat chat) {
        chat.setAssignedAt(clock.instant());
        WaWebChat saved = chats.save(chat);
        WaWebChatAction a = new WaWebChatAction();
        a.setCustomerId(saved.getCustomerId());
        a.setWorkspaceId(saved.getWorkspaceId());
        a.setChatId(saved.getId());
        a.setType(ActionType.UNASSIGNED);
        a.setAt(saved.getAssignedAt());
        a = actions.save(a);
        publisher.publishEvent(new InboxEvents.ChatChanged(saved));
        publisher.publishEvent(new InboxEvents.ActionRecorded(saved, a));
    }

    /** A member of the new owning team, or someone who sees every number of the workspace. */
    private boolean canSee(String userId, Team newTeam, String workspaceId) {
        if (newTeam != null && newTeam.getMemberIds().contains(userId)) return true;
        User user = users.findById(userId).orElse(null);
        if (user == null) return false;
        try {
            return access.inWorkspace(access.load(user), workspaceId).contains(Authority.INBOX_VIEW_ALL.code());
        } catch (NotFoundException noAccess) {
            return false;
        }
    }

    /**
     * The number's pre-migration routing named this very team: the team gets it as its override, once, unless it
     * already has one. A member it named is kept only while still a member.
     */
    private void seedOverrideFromLegacy(PhoneNumber number, Team team) {
        Document legacy = migration.legacyFor(number.getId()).orElse(null);
        if (legacy == null || settings.override(number.getWorkspaceId(), team.getId()).isPresent()) return;
        Document routing = legacy.get("routing", Document.class);
        if (routing == null || !team.getId().equals(routing.getString("teamId"))) return;
        RoutingMode mode;
        try {
            mode = RoutingMode.valueOf(String.valueOf(routing.getString("mode")));
        } catch (IllegalArgumentException unknown) {
            return;
        }
        String userId = routing.getString("userId");
        boolean member = userId != null && team.getMemberIds().contains(userId);
        settings.putOverride(number.getWorkspaceId(), team.getId(), new RoutingSetting.Request(
                mode != RoutingMode.NONE, mode == RoutingMode.ROUND_ROBIN,
                mode == RoutingMode.USER && member ? userId : null, null,
                legacy.getBoolean("claimOnReply", true), legacy.getBoolean("keepAssignmentOnClose", false)));
        log.info("seeded routing override for team {} from the pre-migration setting of number {}", team.getId(),
                number.getId());
    }
}
