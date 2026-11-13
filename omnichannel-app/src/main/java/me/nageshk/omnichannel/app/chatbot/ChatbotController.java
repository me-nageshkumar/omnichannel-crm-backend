package me.nageshk.omnichannel.app.chatbot;

import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import me.nageshk.omnichannel.service.chatbot.ChatbotOptionsService;
import me.nageshk.omnichannel.service.chatbot.ChatbotService;
import me.nageshk.omnichannel.service.chatbot.ChatbotViews.ChatbotSummary;
import me.nageshk.omnichannel.service.chatbot.ChatbotViews.ChatbotView;
import me.nageshk.omnichannel.service.chatbot.ChatbotViews.SaveRequest;
import me.nageshk.omnichannel.service.chatbot.ChatbotViews.SaveResult;
import me.nageshk.omnichannel.service.chatbot.ChatbotViews.VersionView;
import me.nageshk.omnichannel.service.chatbot.ChatbotViews.RunPage;
import me.nageshk.omnichannel.service.chatbot.ChatbotViews.RunView;
import me.nageshk.omnichannel.service.chatbot.ChatbotViews.TestRequest;
import me.nageshk.omnichannel.service.chatbot.ChatbotPreviewRenderer;
import me.nageshk.omnichannel.service.chatbot.ChatbotRunQueryService;
import me.nageshk.omnichannel.service.cloud.CloudMediaService;
import me.nageshk.omnichannel.common.channel.whatsapp.message.CloudMediaUploadResponse;
import me.nageshk.omnichannel.common.channel.whatsapp.message.CloudMessages;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunStatus;
import java.time.Instant;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The chatbots API (spec §8): chatbots, saves with problems, publishing, versions. */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/chatbots")
class ChatbotController {

    record CreateRequest(String name) {
    }

    private final ChatbotService chatbots;
    private final ChatbotOptionsService options;
    private final ChatbotRunQueryService runs;
    private final ChatbotPreviewRenderer preview;
    private final CloudMediaService media;
    private final CloudMessages cloud;

    ChatbotController(ChatbotService chatbots, ChatbotOptionsService options, ChatbotRunQueryService runs,
            ChatbotPreviewRenderer preview, CloudMediaService media, CloudMessages cloud) {
        this.chatbots = chatbots;
        this.options = options;
        this.runs = runs;
        this.preview = preview;
        this.media = media;
        this.cloud = cloud;
    }

    // ---- test, runs, editor helpers (spec §7, §8.1, §8.3) ----

    @PostMapping("/{id}/test")
    @PreAuthorize("@access.has(#workspaceId, 'chatbot:manage')")
    ResponseEntity<RunView> test(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String id, @RequestBody TestRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(runs.test(me, workspaceId, id, req));
    }

    @GetMapping("/{id}/runs")
    @PreAuthorize("@access.has(#workspaceId, 'chatbot:view')")
    RunPage runs(@PathVariable String workspaceId, @PathVariable String id,
            @RequestParam(required = false) ChatbotRunStatus status,
            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return runs.runs(workspaceId, id, status, from, to, page, size);
    }

    @GetMapping("/{id}/runs/{runId}")
    @PreAuthorize("@access.has(#workspaceId, 'chatbot:view')")
    RunView run(@PathVariable String workspaceId, @PathVariable String id, @PathVariable String runId) {
        return runs.run(workspaceId, id, runId);
    }

    @PostMapping("/{id}/runs/{runId}/stop")
    @PreAuthorize("@access.has(#workspaceId, 'chatbot:manage')")
    ResponseEntity<Void> stop(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String id, @PathVariable String runId) {
        runs.stop(me, workspaceId, id, runId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/media")
    @PreAuthorize("@access.has(#workspaceId, 'chatbot:manage')")
    CloudMediaUploadResponse upload(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String id, @RequestPart(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "type", required = false) String type) {
        chatbots.require(workspaceId, id);
        return new CloudMediaUploadResponse(media.upload(me, workspaceId, type, file).id());
    }

    @PostMapping(value = "/{id}/preview", produces = "text/html;charset=UTF-8")
    @PreAuthorize("@access.has(#workspaceId, 'chatbot:manage')")
    String preview(@PathVariable String workspaceId, @PathVariable String id, @RequestBody JsonNode body) {
        chatbots.require(workspaceId, id);
        return preview.html(workspaceId, cloud.parse(body));
    }

    @GetMapping("/options")
    @PreAuthorize("@access.has(#workspaceId, 'chatbot:view')")
    ChatbotOptionsService.ChatbotOptions options(@PathVariable String workspaceId) {
        return options.options(workspaceId);
    }

    @GetMapping
    @PreAuthorize("@access.has(#workspaceId, 'chatbot:view')")
    List<ChatbotSummary> list(@PathVariable String workspaceId) {
        return chatbots.list(workspaceId);
    }

    @PostMapping
    @PreAuthorize("@access.has(#workspaceId, 'chatbot:manage')")
    ResponseEntity<ChatbotView> create(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @RequestBody CreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(chatbots.create(me, workspaceId, req.name()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@access.has(#workspaceId, 'chatbot:view')")
    ChatbotView get(@PathVariable String workspaceId, @PathVariable String id) {
        return chatbots.get(workspaceId, id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@access.has(#workspaceId, 'chatbot:manage')")
    SaveResult save(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String id, @RequestBody SaveRequest req) {
        return chatbots.save(me, workspaceId, id, req);
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("@access.has(#workspaceId, 'chatbot:manage')")
    ChatbotView publish(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String id) {
        return chatbots.publish(me, workspaceId, id);
    }

    @PostMapping("/{id}/unpublish")
    @PreAuthorize("@access.has(#workspaceId, 'chatbot:manage')")
    ChatbotView unpublish(@PathVariable String workspaceId, @PathVariable String id) {
        return chatbots.unpublish(workspaceId, id);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@access.has(#workspaceId, 'chatbot:manage')")
    ResponseEntity<Void> delete(@PathVariable String workspaceId, @PathVariable String id) {
        chatbots.delete(workspaceId, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/versions")
    @PreAuthorize("@access.has(#workspaceId, 'chatbot:view')")
    List<VersionView> versions(@PathVariable String workspaceId, @PathVariable String id) {
        return chatbots.versions(workspaceId, id);
    }

    @GetMapping("/{id}/versions/{n}")
    @PreAuthorize("@access.has(#workspaceId, 'chatbot:view')")
    Map<String, Object> version(@PathVariable String workspaceId, @PathVariable String id, @PathVariable int n) {
        return chatbots.versionFlow(workspaceId, id, n);
    }

    @PostMapping("/{id}/versions/{n}/restore")
    @PreAuthorize("@access.has(#workspaceId, 'chatbot:manage')")
    SaveResult restore(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String id, @PathVariable int n) {
        return chatbots.restore(me, workspaceId, id, n);
    }
}
