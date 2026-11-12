package me.nageshk.omnichannel.service.chatbot;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.db.chatbot.ChatbotSettings;
import me.nageshk.omnichannel.db.chatbot.ChatbotStatus;
import me.nageshk.omnichannel.service.automation.AutomationRef;
import me.nageshk.omnichannel.service.chatbot.node.Problem;

/** The chatbot API's shapes (spec §8). */
public final class ChatbotViews {

    private ChatbotViews() {}

    public record ChatbotView(String id, String name, String description, ChatbotStatus status,
            Map<String, Object> flow, ChatbotSettings settings, int versionNumber, Long version,
            AutomationRef createdBy, Instant creationTime, Instant lastUpdateTime, Instant publishedAt,
            AutomationRef publishedBy) {
    }

    public record ChatbotSummary(String id, String name, ChatbotStatus status, long runs7d, long activeNow,
            Instant lastRunAt, Instant lastUpdateTime) {
    }

    public record VersionView(int versionNumber, Instant savedAt, AutomationRef savedBy) {
    }

    /** What a save sends; {@code name} and {@code description} are optional, {@code version} is required. */
    public record SaveRequest(String name, String description, Map<String, Object> flow, ChatbotSettings settings,
            Long version) {
    }

    public record SaveResult(ChatbotView chatbot, List<Problem> problems) {
    }

    public record ChatbotRef(String id, String name, ChatbotStatus status) {
    }
}
