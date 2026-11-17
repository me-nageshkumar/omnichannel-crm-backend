package me.nageshk.omnichannel.ai.api;

import java.util.List;

/**
 * What a provider can do. {@code reasoningEfforts}: the effort values it accepts, weakest first; empty when it takes
 * none. The list is per provider, not per model: a value the chosen model refuses fails the turn at the provider.
 */
public record AiCapabilities(StateMode state, boolean hostedMcp, boolean vectorStores,
        boolean functionTools, boolean structuredOutput, List<String> reasoningEfforts, boolean temperature,
        boolean webSearch) {

    public AiCapabilities {
        reasoningEfforts = reasoningEfforts == null ? List.of() : List.copyOf(reasoningEfforts);
    }
}
