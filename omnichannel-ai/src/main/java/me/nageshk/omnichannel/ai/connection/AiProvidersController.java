package me.nageshk.omnichannel.ai.connection;

import java.util.List;
import me.nageshk.omnichannel.ai.connection.AiConnectionViews.ProviderView;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The providers the server knows and what each can do; chatbot editors need it too, for the node's pickers. */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/ai/providers")
class AiProvidersController {

    private final AiConnectionService service;

    AiProvidersController(AiConnectionService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("@access.has(#workspaceId, 'ai:view') or @access.has(#workspaceId, 'chatbot:manage')")
    List<ProviderView> list(@PathVariable String workspaceId) {
        return service.providers();
    }
}
