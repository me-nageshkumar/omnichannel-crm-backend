package me.nageshk.omnichannel.ai.mcp;

import java.util.Map;

/** Where and how to reach an MCP server. The header values may be secrets, so they stay out of {@code toString}. */
public record McpEndpoint(String url, Map<String, String> headers) {

    @Override
    public String toString() {
        return "McpEndpoint[" + url + "]";
    }
}
