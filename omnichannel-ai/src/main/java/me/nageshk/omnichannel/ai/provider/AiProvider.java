package me.nageshk.omnichannel.ai.provider;

import java.util.List;
import me.nageshk.omnichannel.ai.api.AiCapabilities;
import me.nageshk.omnichannel.ai.api.AiConnectionField;

/** A provider is a Spring bean; one provider round trip per respond call. */
public interface AiProvider {

    String id();

    String label();

    AiCapabilities capabilities();

    /** The plain settings a connection of this provider takes beside its key; none by default. */
    default List<AiConnectionField> connectionFields() {
        return List.of();
    }

    /** A cheap authenticated call; throws AiProviderException. */
    void verify(AiCredential c);

    List<AiModelInfo> models(AiCredential c);

    /** Empty unless capabilities().vectorStores(). */
    List<VectorStoreInfo> vectorStores(AiCredential c);

    ProviderReply respond(AiCredential c, ProviderRequest r);
}
