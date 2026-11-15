package me.nageshk.omnichannel.ai.mcp;

/** An MCP server could not be reached or refused a request; the message never carries a secret. */
public class McpException extends RuntimeException {

    public McpException(String message) {
        super(message);
    }
}
