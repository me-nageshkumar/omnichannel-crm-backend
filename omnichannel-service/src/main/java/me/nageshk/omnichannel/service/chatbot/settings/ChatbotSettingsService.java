package me.nageshk.omnichannel.service.chatbot.settings;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import me.nageshk.omnichannel.db.chatbot.ChatbotSettings;
import me.nageshk.omnichannel.db.chatbot.ChatbotSettingsDocument;
import me.nageshk.omnichannel.db.chatbot.ChatbotSettingsDocumentRepository;
import me.nageshk.omnichannel.db.chatbot.ChatbotSettingsLayer;
import me.nageshk.omnichannel.db.chatbot.ChatbotSettingsScope;
import me.nageshk.omnichannel.service.chatbot.settings.ChatbotSettingsViews.BrandView;
import me.nageshk.omnichannel.service.chatbot.settings.ChatbotSettingsViews.PlatformView;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/** The platform and brand chatbot settings layers, and resolution of a chatbot's settings over them. */
@Service
public class ChatbotSettingsService {

    private final ChatbotSettingsDocumentRepository repository;
    private final Clock clock;

    public ChatbotSettingsService(ChatbotSettingsDocumentRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    private Optional<ChatbotSettingsDocument> stored(ChatbotSettingsScope scope, String workspaceId) {
        return repository.findByScopeAndWorkspaceId(scope, workspaceId);
    }

    /** The platform layer, always complete: what the super admin saved over the code defaults. */
    public ChatbotSettingsLayer platformLayer() {
        ChatbotSettingsLayer saved = stored(ChatbotSettingsScope.PLATFORM, null).map(ChatbotSettingsDocument::getSettings)
                .orElse(null);
        return SettingsLayers.over(saved, ChatbotSettings.codeDefaults().asLayer());
    }

    public PlatformView platform() {
        Optional<ChatbotSettingsDocument> d = stored(ChatbotSettingsScope.PLATFORM, null);
        return new PlatformView(platformLayer(), d.map(ChatbotSettingsDocument::getVersion).orElse(null),
                d.map(ChatbotSettingsDocument::getLastUpdateTime).orElse(null),
                d.map(ChatbotSettingsDocument::getUpdatedBy).orElse(null));
    }

    public PlatformView savePlatform(ChatbotSettingsLayer settings, Long version, String userId) {
        save(ChatbotSettingsScope.PLATFORM, null, SettingsLayers.check(settings, "settings", true), version, userId);
        return platform();
    }

    private ChatbotSettingsLayer brandLayer(String workspaceId) {
        return stored(ChatbotSettingsScope.BRAND, workspaceId).map(ChatbotSettingsDocument::getSettings)
                .orElse(ChatbotSettingsLayer.empty());
    }

    public BrandView brand(String workspaceId) {
        return new BrandView(brandLayer(workspaceId), platformLayer(),
                stored(ChatbotSettingsScope.BRAND, workspaceId).map(ChatbotSettingsDocument::getVersion).orElse(null));
    }

    public BrandView saveBrand(String workspaceId, ChatbotSettingsLayer own, Long version, String userId) {
        save(ChatbotSettingsScope.BRAND, workspaceId, SettingsLayers.check(own, "own", false), version, userId);
        return brand(workspaceId);
    }

    /** What a chatbot of the brand gets for every field it does not set: the brand's value, else the platform's. */
    public ChatbotSettingsLayer inheritedFor(String workspaceId) {
        return SettingsLayers.over(brandLayer(workspaceId), platformLayer());
    }

    /** Per field, BRAND when the brand sets it, else PLATFORM. */
    public Map<String, SettingSource> inheritedSources(String workspaceId) {
        return SettingsLayers.sources(null, brandLayer(workspaceId));
    }

    /** The settings a run of a chatbot of this brand uses. */
    public ChatbotSettings resolve(String workspaceId, ChatbotSettingsLayer chatbotLayer) {
        return SettingsLayers.resolve(SettingsLayers.over(chatbotLayer, inheritedFor(workspaceId)));
    }

    private void save(ChatbotSettingsScope scope, String workspaceId, ChatbotSettingsLayer layer, Long version,
            String userId) {
        ChatbotSettingsDocument d = stored(scope, workspaceId).orElse(null);
        if (d == null) {
            if (version != null) throw new OptimisticLockingFailureException("chatbot settings changed since they were read");
            d = new ChatbotSettingsDocument();
            d.setScope(scope);
            d.setWorkspaceId(workspaceId);
        } else if (!Objects.equals(version, d.getVersion())) {
            throw new OptimisticLockingFailureException("chatbot settings changed since they were read");
        }
        d.setSettings(layer);
        d.setLastUpdateTime(clock.instant());
        d.setUpdatedBy(userId);
        try {
            repository.save(d);
        } catch (DuplicateKeyException raced) {
            throw new OptimisticLockingFailureException("chatbot settings changed since they were read", raced);
        }
    }
}
