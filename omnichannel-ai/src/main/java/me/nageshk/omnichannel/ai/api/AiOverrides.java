package me.nageshk.omnichannel.ai.api;

public record AiOverrides(String model, String instructions, String reasoningEffort,
        Integer maxOutputTokens, Double temperature) {}
