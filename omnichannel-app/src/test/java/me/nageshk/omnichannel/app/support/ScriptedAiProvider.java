package me.nageshk.omnichannel.app.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingDeque;
import me.nageshk.omnichannel.ai.api.AiCapabilities;
import me.nageshk.omnichannel.ai.api.StateMode;
import me.nageshk.omnichannel.ai.provider.AiCredential;
import me.nageshk.omnichannel.ai.provider.AiModelInfo;
import me.nageshk.omnichannel.ai.provider.AiProvider;
import me.nageshk.omnichannel.ai.provider.AiProviderException;
import me.nageshk.omnichannel.ai.provider.ProviderReply;
import me.nageshk.omnichannel.ai.provider.ProviderRequest;
import me.nageshk.omnichannel.ai.provider.VectorStoreInfo;
import org.springframework.boot.test.context.TestComponent;

/**
 * A provider for integration tests: replies come from a queue the test fills (a {@link ProviderReply} or an
 * {@link AiProviderException}), every request is recorded, and a latch can hold the next reply. Registered only in
 * tests that import it (it is a {@code @TestComponent}, which component scan skips).
 */
@TestComponent
public class ScriptedAiProvider implements AiProvider {

    public static final String BAD_KEY = "bad";

    private final LinkedBlockingDeque<Object> script = new LinkedBlockingDeque<>();
    private final List<ProviderRequest> requests = new CopyOnWriteArrayList<>();
    private final List<String> keys = new CopyOnWriteArrayList<>();
    private volatile CountDownLatch hold;

    @Override
    public String id() {
        return "scripted";
    }

    @Override
    public String label() {
        return "Scripted";
    }

    @Override
    public AiCapabilities capabilities() {
        return new AiCapabilities(StateMode.TRANSCRIPT, false, false, true, true, false, false, false);
    }

    @Override
    public void verify(AiCredential c) {
        if (BAD_KEY.equals(c.apiKey())) throw new AiProviderException(AiProviderException.Code.AUTH, "bad key");
    }

    @Override
    public List<AiModelInfo> models(AiCredential c) {
        return List.of(new AiModelInfo("scripted-1"));
    }

    @Override
    public List<VectorStoreInfo> vectorStores(AiCredential c) {
        return List.of();
    }

    @Override
    public ProviderReply respond(AiCredential c, ProviderRequest r) {
        requests.add(r);
        keys.add(c.apiKey());
        CountDownLatch latch = hold;
        if (latch != null) {
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Object next = script.poll();
        if (next == null) throw new IllegalStateException("ScriptedAiProvider has no reply left");
        if (next instanceof AiProviderException e) throw e;
        return (ProviderReply) next;
    }

    public void reply(ProviderReply reply) {
        script.add(reply);
    }

    public void fail(AiProviderException e) {
        script.add(e);
    }

    /** Holds every reply until {@code latch} opens; null releases the hold. */
    public void holdUntil(CountDownLatch latch) {
        this.hold = latch;
    }

    public List<ProviderRequest> requests() {
        return new ArrayList<>(requests);
    }

    /** The keys the provider was called with, in order. */
    public List<String> keysSeen() {
        return new ArrayList<>(keys);
    }

    public void reset() {
        script.clear();
        requests.clear();
        keys.clear();
        hold = null;
    }
}
