package me.nageshk.omnichannel.ai.profile;

import java.time.Instant;
import java.util.List;

/** The wire shapes of profiles: an MCP secret travels in a request only, never in a view. */
public final class AiProfileViews {

    private AiProfileViews() {}

    /** {@code secret} sets or replaces the server's secret; absent or blank keeps it. A server without {@code id} is new. */
    public record McpSave(String id, String label, String url, ConnectBy connectBy, McpAuth auth, String headerName,
            String secret, List<String> allowedTools) {}

    public record Save(String name, String connectionId, String model, String instructions, String reasoningEffort,
            Integer maxOutputTokens, Double temperature, Boolean webSearch, List<String> vectorStoreIds,
            List<McpSave> mcpServers, Long version) {}

    public record McpView(String id, String label, String url, ConnectBy connectBy, McpAuth auth, String headerName,
            String secretHint, Instant secretSetAt, List<String> allowedTools) {

        static McpView of(McpServer s) {
            return new McpView(s.getId(), s.getLabel(), s.getUrl(), s.getConnectBy(), s.getAuth(), s.getHeaderName(),
                    s.getSecretHint(), s.getSecretSetAt(), List.copyOf(s.getAllowedTools()));
        }
    }

    public record View(String id, String name, String connectionId, String provider, String model,
            String instructions, String reasoningEffort, Integer maxOutputTokens, Double temperature,
            boolean webSearch, List<String> vectorStoreIds, List<McpView> mcpServers, Instant updatedAt,
            Long version) {

        static View of(AiProfile p, String provider) {
            return new View(p.getId(), p.getName(), p.getConnectionId(), provider, p.getModel(), p.getInstructions(),
                    p.getReasoningEffort(), p.getMaxOutputTokens(), p.getTemperature(), p.isWebSearch(),
                    List.copyOf(p.getVectorStoreIds()), p.getMcpServers().stream().map(McpView::of).toList(),
                    p.getUpdatedAt(), p.getVersion());
        }
    }
}
