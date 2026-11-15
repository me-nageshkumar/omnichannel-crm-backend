package me.nageshk.omnichannel.ai.api;

public record AiUsage(long inputTokens, long outputTokens, long cachedTokens, long reasoningTokens) {}
