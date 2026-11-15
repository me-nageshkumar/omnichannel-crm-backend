package me.nageshk.omnichannel.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import me.nageshk.omnichannel.ai.api.AiCapabilities;
import me.nageshk.omnichannel.ai.api.StateMode;
import me.nageshk.omnichannel.common.api.NotFoundException;
import org.junit.jupiter.api.Test;

class AiProvidersTest {

    private static AiProvider provider(String id) {
        return new AiProvider() {
            public String id() { return id; }
            public String label() { return id; }
            public AiCapabilities capabilities() {
                return new AiCapabilities(StateMode.CHAINED, false, false, true, true, false, false, false);
            }
            public void verify(AiCredential c) {}
            public List<AiModelInfo> models(AiCredential c) { return List.of(); }
            public List<VectorStoreInfo> vectorStores(AiCredential c) { return List.of(); }
            public ProviderReply respond(AiCredential c, ProviderRequest r) { return null; }
        };
    }

    @Test
    void duplicateIdsFailAtStartup() {
        assertThatThrownBy(() -> new AiProviders(List.of(provider("openai"), provider("openai"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("openai");
    }

    @Test
    void unknownIdIsNotFound() {
        AiProviders providers = new AiProviders(List.of(provider("openai")));
        assertThat(providers.of("openai").id()).isEqualTo("openai");
        assertThat(providers.all()).hasSize(1);
        assertThatThrownBy(() -> providers.of("claude")).isInstanceOf(NotFoundException.class);
        assertThat(new AiProviders(List.of()).all()).isEmpty();
    }
}
