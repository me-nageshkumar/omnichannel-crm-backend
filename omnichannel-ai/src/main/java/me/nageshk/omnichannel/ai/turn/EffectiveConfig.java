package me.nageshk.omnichannel.ai.turn;

import me.nageshk.omnichannel.ai.api.AiOverrides;
import me.nageshk.omnichannel.ai.profile.AiProfile;

/** The settings one turn runs with: field by field the node's override, else the profile's (null = provider default). */
public record EffectiveConfig(String model, String instructions, String reasoningEffort, Integer maxOutputTokens,
        Double temperature) {

    /** {@code extraInstructions} (the node's, rendered) go after the instructions, after a blank line. */
    public static EffectiveConfig of(AiProfile p, AiOverrides o, String extraInstructions) {
        AiOverrides n = o == null ? new AiOverrides(null, null, null, null, null) : o;
        String instructions = pick(n.instructions(), p.getInstructions());
        if (notBlank(extraInstructions)) {
            instructions = instructions == null ? extraInstructions : instructions + "\n\n" + extraInstructions;
        }
        return new EffectiveConfig(pick(n.model(), p.getModel()), instructions,
                pick(n.reasoningEffort(), p.getReasoningEffort()),
                n.maxOutputTokens() != null ? n.maxOutputTokens() : p.getMaxOutputTokens(),
                n.temperature() != null ? n.temperature() : p.getTemperature());
    }

    private static String pick(String node, String profile) {
        if (notBlank(node)) return node;
        return notBlank(profile) ? profile : null;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
