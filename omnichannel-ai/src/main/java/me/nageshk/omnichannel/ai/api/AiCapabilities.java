package me.nageshk.omnichannel.ai.api;

public record AiCapabilities(StateMode state, boolean hostedMcp, boolean vectorStores,
        boolean functionTools, boolean structuredOutput, boolean reasoningEffort, boolean temperature,
        boolean webSearch) {}
