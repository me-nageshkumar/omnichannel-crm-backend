package me.nageshk.omnichannel.inbox;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import me.nageshk.omnichannel.access.AccessService;
import me.nageshk.omnichannel.access.Authority;
import me.nageshk.omnichannel.phone.PhoneNumber;
import me.nageshk.omnichannel.tenant.Team;
import me.nageshk.omnichannel.api.NotFoundException;
import me.nageshk.omnichannel.api.ValidationException;
import me.nageshk.omnichannel.auth.AccessTokenAuthenticator;
import me.nageshk.omnichannel.auth.AuthenticatedUser;
import me.nageshk.omnichannel.inbox.InboxScope.ListScope;
import me.nageshk.omnichannel.phone.PhoneNumberService;
import me.nageshk.omnichannel.phone.PhoneNumberView;
import me.nageshk.omnichannel.tenant.TeamService;
import me.nageshk.omnichannel.user.Availability;
import me.nageshk.omnichannel.user.User;
import me.nageshk.omnichannel.user.UserAdminService;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The inbox of a brand's WhatsApp Web numbers: {@code inbox:view} reads what the user's scope covers,
 * {@code inbox:send} sends, closes and reopens, {@code inbox:assign} assigns, {@code inbox:settings} edits routing.
 * A chat outside the user's scope is 404.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/inbox")
public class InboxController {

    private static final String BEARER = "Bearer ";
    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 100;

    /** Which statuses the chat list shows. */
    public enum StatusFilter { OPEN, CLOSED, ALL }

    public record AssignRequest(String userId, String teamId) {
    }

    public record Person(String id, String name, String username, Availability.Status availability) {
    }

    public record TeamRef(String id, String name) {
    }

    public record Assignees(List<Person> users, List<TeamRef> teams) {
    }

    private final InboxQueryService query;
    private final InboxScopeService scopeService;
    private final InboxStreamBroker broker;
    private final AccessTokenAuthenticator authenticator;
    private final InboxViews views;
    private final InboxSettingService settings;
    private final ChatAssignmentService assignment;
    private final PhoneNumberService phoneNumbers;
    private final UserAdminService userAdmin;
    private final TeamService teamService;
    private final InboxSendService sender;
    private final WaWebMessageRepository messageRepository;
    private final ChatMediaService chatMedia;
    private final NewChatService newChats;
    private final NumberLookupService numberLookups;
    private final AccessService accessService;

    public InboxController(InboxQueryService query, InboxScopeService scopeService, InboxStreamBroker broker,
            AccessTokenAuthenticator authenticator, InboxViews views, InboxSettingService settings, ChatAssignmentService assignment,
            PhoneNumberService phoneNumbers, UserAdminService userAdmin, TeamService teamService,
            InboxSendService sender, WaWebMessageRepository messageRepository, ChatMediaService chatMedia,
            NewChatService newChats, NumberLookupService numberLookups, AccessService accessService) {
        this.accessService = accessService;
        this.query = query;
        this.scopeService = scopeService;
        this.broker = broker;
        this.authenticator = authenticator;
        this.views = views;
        this.settings = settings;
        this.assignment = assignment;
        this.phoneNumbers = phoneNumbers;
        this.userAdmin = userAdmin;
        this.teamService = teamService;
        this.sender = sender;
        this.messageRepository = messageRepository;
        this.chatMedia = chatMedia;
        this.newChats = newChats;
        this.numberLookups = numberLookups;
    }

    @GetMapping("/phone-numbers")
    @PreAuthorize("@access.has(#workspaceId, 'inbox:view')")
    public List<PhoneNumberView> numbers(@AuthenticationPrincipal AuthenticatedUser me,
            @PathVariable String workspaceId) {
        List<InboxScopeService.VisibleNumber> visible = scopeService.visibleNumbers(me, workspaceId);
        List<PhoneNumberView> views = phoneNumbers.views(visible.stream().map(InboxScopeService.VisibleNumber::number)
                .toList());
        List<PhoneNumberView> flagged = new ArrayList<>(views.size());
        for (int i = 0; i < views.size(); i++) flagged.add(views.get(i).asGuest(visible.get(i).guest()));
        return flagged;
    }

    @GetMapping("/chats")
    @PreAuthorize("@access.has(#workspaceId, 'inbox:view')")
    public ChatsPage chats(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @RequestParam(required = false) List<String> phoneNumberIds, @RequestParam(required = false) ListScope scope,
            @RequestParam(defaultValue = "OPEN") StatusFilter status, @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "false") boolean unread, @RequestParam(required = false) Instant before,
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        ChatStatus wanted = status == StatusFilter.ALL ? null : ChatStatus.valueOf(status.name());
        return query.chats(me, workspaceId, phoneNumberIds == null ? List.of() : phoneNumberIds, scope, wanted, q,
                unread, before, checked(limit));
    }

    @GetMapping("/phone-numbers/{phoneNumberId}/chats")
    @PreAuthorize("@access.has(#workspaceId, 'inbox:view')")
    public ChatsPage chats(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String phoneNumberId, @RequestParam(required = false) ListScope scope,
            @RequestParam(defaultValue = "OPEN") StatusFilter status, @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "false") boolean unread, @RequestParam(required = false) Instant before,
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        phoneNumbers.requireInWorkspace(workspaceId, phoneNumberId);
        ChatStatus wanted = status == StatusFilter.ALL ? null : ChatStatus.valueOf(status.name());
        return query.chats(me, workspaceId, List.of(phoneNumberId), scope, wanted, q, unread, before, checked(limit));
    }

    @GetMapping(value = "/phone-numbers/{phoneNumberId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("@access.has(#workspaceId, 'inbox:view')")
    public SseEmitter stream(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @PathVariable String workspaceId, @PathVariable String phoneNumberId) {
        phoneNumbers.requireInWorkspace(workspaceId, phoneNumberId);
        // the stream outlives the request, so the viewer is re-authenticated from the token on every push and ping:
        // a user who is disabled, logged out, demoted or removed from the brand stops receiving at once
        String token = authorization.startsWith(BEARER) ? authorization.substring(BEARER.length()) : authorization;
        return broker.subscribe(phoneNumberId, () -> authenticator.authenticate(token)
                .map(user -> scopeService.scopeOf(user, workspaceId))
                .orElseThrow(() -> new NotFoundException("session ended")));
    }

    @GetMapping("/settings")
    @PreAuthorize("@access.has(#workspaceId, 'inbox:settings')")
    public RoutingSetting defaultSettings(@PathVariable String workspaceId) {
        return settings.defaultView(workspaceId);
    }

    @PutMapping("/settings")
    @PreAuthorize("@access.has(#workspaceId, 'inbox:settings')")
    public RoutingSetting updateDefaultSettings(@PathVariable String workspaceId,
            @RequestBody(required = false) RoutingSetting.Request req) {
        settings.putDefault(workspaceId, req);
        return settings.defaultView(workspaceId);
    }

    @GetMapping("/settings/teams")
    @PreAuthorize("@access.has(#workspaceId, 'inbox:settings')")
    public List<RoutingSetting> teamSettings(@PathVariable String workspaceId) {
        return settings.teamSettings(workspaceId);
    }

    @GetMapping("/settings/teams/{teamId}")
    @PreAuthorize("@access.has(#workspaceId, 'inbox:settings')")
    public RoutingSetting teamSettings(@PathVariable String workspaceId, @PathVariable String teamId) {
        return settings.teamView(workspaceId, teamId);
    }

    @PutMapping("/settings/teams/{teamId}")
    @PreAuthorize("@access.has(#workspaceId, 'inbox:settings')")
    public RoutingSetting updateTeamSettings(@PathVariable String workspaceId, @PathVariable String teamId,
            @RequestBody(required = false) RoutingSetting.Request req) {
        settings.putOverride(workspaceId, teamId, req);
        return settings.teamView(workspaceId, teamId);
    }

    @DeleteMapping("/settings/teams/{teamId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@access.has(#workspaceId, 'inbox:settings')")
    public void deleteTeamSettings(@PathVariable String workspaceId, @PathVariable String teamId) {
        settings.deleteOverride(workspaceId, teamId);
    }

    /**
     * Who a chat may be assigned to. With {@code phoneNumberId}, only people who can open that number: the owning
     * team's members and holders of {@code inbox:view-all}; a number nobody owns lists everyone, as before.
     */
    @GetMapping("/assignees")
    @PreAuthorize("@access.has(#workspaceId, 'inbox:view-queue') or @access.has(#workspaceId, 'inbox:assign')")
    public Assignees assignees(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @RequestParam(required = false) String phoneNumberId) {
        Team owner = null;
        if (phoneNumberId != null) {
            PhoneNumber number = phoneNumbers.requireInWorkspace(workspaceId, phoneNumberId);
            if (!scopeService.visibleNumbers(me, workspaceId).stream().anyMatch(v -> v.number().getId().equals(number.getId()))) {
                throw new NotFoundException("phone number not found");
            }
            owner = number.getTeamId() == null ? null
                    : teamService.list(me.customerId(), workspaceId).stream().map(TeamService.TeamWithMembers::team)
                            .filter(t -> t.getId().equals(number.getTeamId())).findFirst().orElse(null);
        }
        Team owningTeam = owner;
        List<Person> users = userAdmin.withAccessTo(me.customerId(), workspaceId).stream()
                .filter(u -> u.isActive() && UserAdminService.brandDisabled(u, workspaceId) == null)
                .filter(u -> owningTeam == null || owningTeam.getMemberIds().contains(u.getId())
                        || accessService.inWorkspace(accessService.load(u), workspaceId).contains(Authority.INBOX_VIEW_ALL.code()))
                .map(InboxController::person).toList();
        List<TeamRef> teams = teamService.list(me.customerId(), workspaceId).stream()
                .map(t -> new TeamRef(t.team().getId(), t.team().getName())).toList();
        return new Assignees(users, teams);
    }

    @GetMapping("/chats/{chatId}")
    @PreAuthorize("@access.has(#workspaceId, 'inbox:view')")
    public ChatView chat(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String chatId) {
        return views.chat(query.requireVisible(me, workspaceId, chatId));
    }

    @GetMapping("/chats/{chatId}/thread")
    @PreAuthorize("@access.has(#workspaceId, 'inbox:view')")
    public ThreadPage thread(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String chatId, @RequestParam(required = false) Instant before,
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        return query.thread(query.requireVisible(me, workspaceId, chatId), before, checked(limit));
    }

    @PostMapping("/chats/{chatId}/read")
    @PreAuthorize("@access.has(#workspaceId, 'inbox:view')")
    public ChatView read(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String chatId) {
        return views.chat(query.markRead(query.requireVisible(me, workspaceId, chatId)));
    }

    /** Whether a phone is on WhatsApp, from the cache or the number's device; see {@link NumberLookupService}. */
    @GetMapping("/phone-numbers/{phoneNumberId}/number-lookup")
    @PreAuthorize("@access.has(#workspaceId, 'inbox:send')")
    public CompletableFuture<NumberLookup> numberLookup(@PathVariable String workspaceId,
            @PathVariable String phoneNumberId, @RequestParam String phone,
            @RequestParam(defaultValue = "false") boolean force) {
        return numberLookups.lookup(workspaceId, phoneNumberId, phone, force);
    }

    /** Starts (or lands in) the chat of a phone number and sends its first message; see {@link NewChatService}. */
    @PostMapping("/phone-numbers/{phoneNumberId}/chats")
    @PreAuthorize("@access.has(#workspaceId, 'inbox:send')")
    public CompletableFuture<MessageView> startChat(@AuthenticationPrincipal AuthenticatedUser me,
            @PathVariable String workspaceId, @PathVariable String phoneNumberId,
            @RequestBody(required = false) NewChatRequest req) {
        return newChats.start(me, workspaceId, phoneNumberId, req);
    }

    /** Sends a text or a template to the chat through the number's device; see {@link InboxSendService}. */
    @PostMapping("/chats/{chatId}/messages")
    @PreAuthorize("@access.has(#workspaceId, 'inbox:send')")
    public CompletableFuture<MessageView> send(@AuthenticationPrincipal AuthenticatedUser me,
            @PathVariable String workspaceId, @PathVariable String chatId,
            @RequestBody(required = false) SendRequest req) {
        return sender.send(me, workspaceId, query.requireVisible(me, workspaceId, chatId), req);
    }

    /**
     * The file of a message, fetched from the phone the first time. Images, video and audio display inline; anything
     * else (and SVG, which can carry script) downloads.
     */
    @GetMapping("/chats/{chatId}/messages/{messageId}/media")
    @PreAuthorize("@access.has(#workspaceId, 'inbox:view')")
    public CompletableFuture<ResponseEntity<Resource>> media(@AuthenticationPrincipal AuthenticatedUser me,
            @PathVariable String workspaceId, @PathVariable String chatId, @PathVariable String messageId) {
        WaWebChat chat = query.requireVisible(me, workspaceId, chatId);
        WaWebMessage message = messageRepository.findByIdAndChatId(messageId, chat.getId())
                .orElseThrow(() -> new NotFoundException("message not found"));
        return chatMedia.fetchIfNeeded(chat, message).thenApply(InboxController::download);
    }

    private static ResponseEntity<Resource> download(ChatMediaService.MediaFile f) {
        String type = f.mimeType() == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : f.mimeType();
        boolean inline;
        try {
            MediaType parsed = MediaType.parseMediaType(type);
            String main = parsed.getType();
            inline = !parsed.getSubtype().toLowerCase(Locale.ROOT).startsWith("svg")
                    && (main.equalsIgnoreCase("image") || main.equalsIgnoreCase("video")
                    || main.equalsIgnoreCase("audio"));
        } catch (RuntimeException e) {
            inline = false;
        }
        ContentDisposition disposition = (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(f.fileName(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, type)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentLength(f.size())
                .body(f.resource());
    }

    @PostMapping("/chats/{chatId}/assign")
    @PreAuthorize("@access.has(#workspaceId, 'inbox:assign')")
    public ChatView assign(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String chatId, @RequestBody(required = false) AssignRequest req) {
        WaWebChat chat = query.requireVisible(me, workspaceId, chatId);
        return views.chat(req == null ? assignment.assign(me, chat, null, null)
                : assignment.assign(me, chat, req.userId(), req.teamId()));
    }

    @PostMapping("/chats/{chatId}/claim")
    @PreAuthorize("@access.has(#workspaceId, 'inbox:send')")
    public ChatView claim(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String chatId) {
        return views.chat(assignment.claim(me, query.requireVisible(me, workspaceId, chatId)));
    }

    @PostMapping("/chats/{chatId}/handoff")
    @PreAuthorize("@access.has(#workspaceId, 'inbox:send')")
    public ChatView handoff(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String chatId, @RequestBody(required = false) HandoffRequest req) {
        WaWebChat chat = query.requireVisible(me, workspaceId, chatId);
        return views.chat(assignment.handoff(me, chat, req, accessService.has(workspaceId, "inbox:assign")));
    }

    @PostMapping("/chats/{chatId}/unassign")
    @PreAuthorize("@access.has(#workspaceId, 'inbox:assign')")
    public ChatView unassign(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String chatId) {
        return views.chat(assignment.unassign(me, query.requireVisible(me, workspaceId, chatId)));
    }

    @PostMapping("/chats/{chatId}/close")
    @PreAuthorize("@access.has(#workspaceId, 'inbox:send')")
    public ChatView close(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String chatId) {
        return views.chat(assignment.close(me, query.requireVisible(me, workspaceId, chatId)));
    }

    @PostMapping("/chats/{chatId}/reopen")
    @PreAuthorize("@access.has(#workspaceId, 'inbox:send')")
    public ChatView reopen(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String chatId) {
        return views.chat(assignment.reopen(me, query.requireVisible(me, workspaceId, chatId)));
    }

    private static int checked(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) throw new ValidationException("limit", "must be between 1 and " + MAX_LIMIT);
        return limit;
    }

    private static Person person(User u) {
        return new Person(u.getId(), u.getName(), u.getUsername(), u.availabilityStatus());
    }

}
