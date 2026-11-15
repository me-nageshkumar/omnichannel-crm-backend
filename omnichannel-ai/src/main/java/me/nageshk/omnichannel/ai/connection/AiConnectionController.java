package me.nageshk.omnichannel.ai.connection;

import java.util.List;
import me.nageshk.omnichannel.ai.connection.AiConnectionViews.Save;
import me.nageshk.omnichannel.ai.connection.AiConnectionViews.View;
import me.nageshk.omnichannel.ai.provider.AiModelInfo;
import me.nageshk.omnichannel.ai.provider.VectorStoreInfo;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** A brand's AI connections: provider accounts with write-only keys (spec 2026-11-15 §5.7–§5.8). */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/ai/connections")
class AiConnectionController {

    private final AiConnectionService service;

    AiConnectionController(AiConnectionService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("@access.has(#workspaceId, 'ai:view')")
    List<View> list(@PathVariable String workspaceId) {
        return service.list(workspaceId);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@access.has(#workspaceId, 'ai:view')")
    View get(@PathVariable String workspaceId, @PathVariable String id) {
        return service.get(workspaceId, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.has(#workspaceId, 'ai:manage')")
    View create(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @RequestBody Save body) {
        return service.create(workspaceId, me.customerId(), me.userId(), body);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@access.has(#workspaceId, 'ai:manage')")
    View replace(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String id, @RequestBody Save body) {
        return service.replace(workspaceId, id, me.userId(), body);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@access.has(#workspaceId, 'ai:manage')")
    void delete(@PathVariable String workspaceId, @PathVariable String id) {
        service.delete(workspaceId, id);
    }

    @PostMapping("/{id}/verify")
    @PreAuthorize("@access.has(#workspaceId, 'ai:manage')")
    View verify(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String id) {
        return service.verify(workspaceId, id, me.userId());
    }

    @GetMapping("/{id}/models")
    @PreAuthorize("@access.has(#workspaceId, 'ai:view')")
    List<AiModelInfo> models(@PathVariable String workspaceId, @PathVariable String id) {
        return service.models(workspaceId, id);
    }

    @GetMapping("/{id}/vector-stores")
    @PreAuthorize("@access.has(#workspaceId, 'ai:view')")
    List<VectorStoreInfo> vectorStores(@PathVariable String workspaceId, @PathVariable String id) {
        return service.vectorStores(workspaceId, id);
    }
}
