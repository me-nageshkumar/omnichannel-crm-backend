package me.nageshk.omnichannel.ai.turn;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.ai.api.AiCapabilities;
import me.nageshk.omnichannel.ai.api.AiUsage;
import me.nageshk.omnichannel.ai.api.StateMode;
import me.nageshk.omnichannel.ai.provider.AiCredential;
import me.nageshk.omnichannel.ai.provider.AiModelInfo;
import me.nageshk.omnichannel.ai.provider.AiProvider;
import me.nageshk.omnichannel.ai.provider.AiProviderException;
import me.nageshk.omnichannel.ai.provider.ProviderReply;
import me.nageshk.omnichannel.ai.provider.ProviderRequest;
import me.nageshk.omnichannel.ai.provider.ToolCall;
import me.nageshk.omnichannel.ai.provider.VectorStoreInfo;

/** The module's own test provider: replies come from a script, requests and keys are recorded. */
class ScriptedProvider implements AiProvider {

    private final String id;
    private final AiCapabilities capabilities;
    private final Deque<Object> script = new ArrayDeque<>();
    final List<ProviderRequest> requests = new ArrayList<>();
    final List<String> keys = new ArrayList<>();

    ScriptedProvider(String id, StateMode state) {
        this.id = id;
        this.capabilities = new AiCapabilities(state, true, true, true, true, List.of("minimal", "low", "medium", "high"), true, true);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String label() {
        return id;
    }

    @Override
    public AiCapabilities capabilities() {
        return capabilities;
    }

    @Override
    public void verify(AiCredential c) {
    }

    @Override
    public List<AiModelInfo> models(AiCredential c) {
        return List.of();
    }

    @Override
    public List<VectorStoreInfo> vectorStores(AiCredential c) {
        return List.of();
    }

    @Override
    public ProviderReply respond(AiCredential c, ProviderRequest r) {
        requests.add(r);
        keys.add(c.apiKey());
        Object next = script.poll();
        if (next == null) throw new IllegalStateException("no scripted reply left");
        if (next instanceof AiProviderException e) throw e;
        return (ProviderReply) next;
    }

    /** A text reply; a CHAINED provider's state is {responseId: id}. */
    ScriptedProvider text(String responseId, String text) {
        script.add(reply(responseId, text, List.of(), false));
        return this;
    }

    ScriptedProvider calls(String responseId, ToolCall... calls) {
        script.add(reply(responseId, "", List.of(calls), false));
        return this;
    }

    ScriptedProvider add(ProviderReply r) {
        script.add(r);
        return this;
    }

    ScriptedProvider fail(AiProviderException e) {
        script.add(e);
        return this;
    }

    ProviderReply reply(String responseId, String text, List<ToolCall> calls, boolean hosted) {
        Map<String, Object> state = capabilities.state() == StateMode.CHAINED ? Map.of("responseId", responseId) : Map.of();
        return new ProviderReply(text, calls, state, new AiUsage(10, 5, 1, 0), responseId, hosted);
    }

    ProviderRequest last() {
        return requests.get(requests.size() - 1);
    }
}
