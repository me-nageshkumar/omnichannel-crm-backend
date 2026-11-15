package me.nageshk.omnichannel.ai.provider;

import java.util.List;
import me.nageshk.omnichannel.ai.api.AiCapabilities;

/** A provider is a Spring bean; one provider round trip per respond call. */
public interface AiProvider {

    String id();

    String label();

    AiCapabilities capabilities();

    /** A cheap authenticated call; throws AiProviderException. */
    void verify(AiCredential c);

    List<AiModelInfo> models(AiCredential c);

    /** Empty unless capabilities().vectorStores(). */
    List<VectorStoreInfo> vectorStores(AiCredential c);

    ProviderReply respond(AiCredential c, ProviderRequest r);
}
