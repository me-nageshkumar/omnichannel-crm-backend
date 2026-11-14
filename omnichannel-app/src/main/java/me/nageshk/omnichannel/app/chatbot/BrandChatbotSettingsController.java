package me.nageshk.omnichannel.app.chatbot;

import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import me.nageshk.omnichannel.service.chatbot.settings.ChatbotSettingsService;
import me.nageshk.omnichannel.service.chatbot.settings.ChatbotSettingsViews.BrandSave;
import me.nageshk.omnichannel.service.chatbot.settings.ChatbotSettingsViews.BrandView;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A brand's own chatbot settings layer (spec 2026-11-14 §6–§7). */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/chatbots/settings")
class BrandChatbotSettingsController {

    private final ChatbotSettingsService service;

    BrandChatbotSettingsController(ChatbotSettingsService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("@access.has(#workspaceId, 'chatbot:view')")
    BrandView get(@PathVariable String workspaceId) {
        return service.brand(workspaceId);
    }

    @PutMapping
    @PreAuthorize("@access.has(#workspaceId, 'chatbot:manage')")
    BrandView put(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @RequestBody BrandSave body) {
        return service.saveBrand(workspaceId, body.own(), body.version(), me.userId());
    }
}
