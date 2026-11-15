package me.nageshk.omnichannel.ai.api;

public record AiProfileInfo(String id, String name, String provider, AiCapabilities capabilities,
        String model, String instructions, String reasoningEffort, Integer maxOutputTokens, Double temperature) {}
