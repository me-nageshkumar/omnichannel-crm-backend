package me.nageshk.omnichannel.app.chatbot;

import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import me.nageshk.omnichannel.service.chatbot.settings.ChatbotSettingsService;
import me.nageshk.omnichannel.service.chatbot.settings.ChatbotSettingsViews.PlatformSave;
import me.nageshk.omnichannel.service.chatbot.settings.ChatbotSettingsViews.PlatformView;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The chatbot settings every brand starts with (spec 2026-11-14 §6–§7). */
@RestController
@RequestMapping("/api/platform/chatbot-settings")
class PlatformChatbotSettingsController {

    private final ChatbotSettingsService service;

    PlatformChatbotSettingsController(ChatbotSettingsService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('chatbot-defaults:manage')")
    PlatformView get() {
        return service.platform();
    }

    @PutMapping
    @PreAuthorize("hasAuthority('chatbot-defaults:manage')")
    PlatformView put(@AuthenticationPrincipal AuthenticatedUser me, @RequestBody PlatformSave body) {
        return service.savePlatform(body.settings(), body.version(), me.userId());
    }
}
