package me.nageshk.omnichannel.ai.provider;

public sealed interface AiItem {

    record UserText(String text) implements AiItem {}

    record AssistantText(String text) implements AiItem {}

    record ToolCallItem(String callId, String name, String argumentsJson) implements AiItem {}

    record ToolResultItem(String callId, String output) implements AiItem {}
}
