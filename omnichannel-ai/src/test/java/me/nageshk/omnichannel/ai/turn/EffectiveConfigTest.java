package me.nageshk.omnichannel.ai.turn;

import static org.assertj.core.api.Assertions.assertThat;

import me.nageshk.omnichannel.ai.api.AiOverrides;
import me.nageshk.omnichannel.ai.profile.AiProfile;
import org.junit.jupiter.api.Test;

class EffectiveConfigTest {

    private static AiProfile profile(String instructions) {
        AiProfile p = new AiProfile();
        p.setModel("gpt-5");
        p.setInstructions(instructions);
        p.setReasoningEffort("low");
        p.setMaxOutputTokens(500);
        p.setTemperature(0.4);
        return p;
    }

    @Test
    void nodeOverridesWin() {
        EffectiveConfig c = EffectiveConfig.of(profile("Profile."),
                new AiOverrides("gpt-5-mini", "Node.", "high", 900, 1.2), null);

        assertThat(c).isEqualTo(new EffectiveConfig("gpt-5-mini", "Node.", "high", 900, 1.2));
    }

    @Test
    void nullInherits() {
        EffectiveConfig expected = new EffectiveConfig("gpt-5", "Profile.", "low", 500, 0.4);

        assertThat(EffectiveConfig.of(profile("Profile."), null, null)).isEqualTo(expected);
        assertThat(EffectiveConfig.of(profile("Profile."), new AiOverrides(null, null, null, null, null), null))
                .isEqualTo(expected);
        assertThat(EffectiveConfig.of(profile("Profile."), new AiOverrides(" ", "", null, null, null), "  "))
                .as("blank overrides and extra instructions inherit").isEqualTo(expected);
    }

    @Test
    void extraInstructionsAppended() {
        assertThat(EffectiveConfig.of(profile("Profile."), null, "The customer is Asha.").instructions())
                .isEqualTo("Profile.\n\nThe customer is Asha.");
        assertThat(EffectiveConfig.of(profile("Profile."), new AiOverrides(null, "Node.", null, null, null),
                "Extra.").instructions()).isEqualTo("Node.\n\nExtra.");
        assertThat(EffectiveConfig.of(profile(null), null, "Only extra.").instructions()).isEqualTo("Only extra.");
    }
}
