package me.nageshk.omnichannel.app.inbox;

import java.time.Instant;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.db.inbox.HistoryChatStatus;
import me.nageshk.omnichannel.db.inbox.HistoryImportStatus;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import me.nageshk.omnichannel.service.inbox.history.HistoryImportCounter;
import me.nageshk.omnichannel.service.inbox.history.HistoryImportRequest;
import me.nageshk.omnichannel.service.inbox.history.HistoryImportService;
import me.nageshk.omnichannel.service.inbox.history.HistoryImportView;
import me.nageshk.omnichannel.service.inbox.history.HistoryImportViews;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Imports of a number's WhatsApp Web chat history. */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/inbox")
class HistoryImportController {

    /** The optional window of a single-chat import. */
    public record RangeRequest(Instant from, Instant to) {}

    private final HistoryImportService service;
    private final HistoryImportViews views;
    private final HistoryImportCounter counter;

    HistoryImportController(HistoryImportService service, HistoryImportViews views, HistoryImportCounter counter) {
        this.service = service;
        this.views = views;
        this.counter = counter;
    }

    @PostMapping("/phone-numbers/{phoneNumberId}/history-imports")
    @PreAuthorize("@access.has(#workspaceId, 'inbox:import-history')")
    ResponseEntity<HistoryImportView> create(@AuthenticationPrincipal AuthenticatedUser me,
            @PathVariable String workspaceId, @PathVariable String phoneNumberId,
            @RequestBody HistoryImportRequest req) {
        if (req == null || req.scope() == null || req.scope().kind() == null)
            throw new ValidationException("scope", "scope is required");
        return respond(service.create(me, workspaceId, phoneNumberId, req));
    }

    @PostMapping("/chats/{chatId}/history-imports")
    @PreAuthorize("@access.has(#workspaceId, 'inbox:import-history')")
    ResponseEntity<HistoryImportView> createForChat(@AuthenticationPrincipal AuthenticatedUser me,
            @PathVariable String workspaceId, @PathVariable String chatId, @RequestBody RangeRequest req) {
        return respond(service.createForChat(me, workspaceId, chatId, req.from(), req.to()));
    }

    @GetMapping("/chats/{chatId}/history-imports/active")
    @PreAuthorize("@access.has(#workspaceId, 'inbox:import-history')")
    ResponseEntity<HistoryImportView> activeForChat(@PathVariable String workspaceId, @PathVariable String chatId) {
        return service.activeForChat(workspaceId, chatId).map(views::view).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/history-imports")
    @PreAuthorize("@access.has(#workspaceId, 'inbox:import-history')")
    HistoryImportService.ImportPage list(@PathVariable String workspaceId,
            @RequestParam(required = false) String phoneNumberId,
            @RequestParam(required = false) HistoryImportStatus status,
            @RequestParam(defaultValue = "0") int page) {
        return service.list(workspaceId, phoneNumberId, status, page);
    }

    @GetMapping("/history-imports/{id}")
    @PreAuthorize("@access.has(#workspaceId, 'inbox:import-history')")
    HistoryImportView get(@PathVariable String workspaceId, @PathVariable String id) {
        service.require(workspaceId, id);
        counter.refresh(id);
        return views.view(service.require(workspaceId, id));
    }

    @GetMapping("/history-imports/{id}/chats")
    @PreAuthorize("@access.has(#workspaceId, 'inbox:import-history')")
    HistoryImportService.ChatPage chats(@PathVariable String workspaceId, @PathVariable String id,
            @RequestParam(required = false) HistoryChatStatus status, @RequestParam(defaultValue = "0") int page) {
        return service.chats(workspaceId, id, status, page);
    }

    @PostMapping("/history-imports/{id}/pause")
    @PreAuthorize("@access.has(#workspaceId, 'inbox:import-history')")
    HistoryImportView pause(@PathVariable String workspaceId, @PathVariable String id) {
        return views.view(service.pause(workspaceId, id));
    }

    @PostMapping("/history-imports/{id}/resume")
    @PreAuthorize("@access.has(#workspaceId, 'inbox:import-history')")
    HistoryImportView resume(@PathVariable String workspaceId, @PathVariable String id) {
        return views.view(service.resume(workspaceId, id));
    }

    @PostMapping("/history-imports/{id}/cancel")
    @PreAuthorize("@access.has(#workspaceId, 'inbox:import-history')")
    HistoryImportView cancel(@PathVariable String workspaceId, @PathVariable String id) {
        return views.view(service.cancel(workspaceId, id));
    }

    private ResponseEntity<HistoryImportView> respond(HistoryImportService.Created created) {
        return ResponseEntity.status(created.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(views.view(created.imp()));
    }
}
