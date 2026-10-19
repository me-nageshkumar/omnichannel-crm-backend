package me.nageshk.omnichannel.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import me.nageshk.omnichannel.config.OmnichannelProperties;
import me.nageshk.omnichannel.ws.DeviceSession;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Sends request frames to a device and completes the matching future when its response frame arrives. */
@Component
public class RpcDispatcher {

    private record Pending(String username, CompletableFuture<JsonNode> future) {}

    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();
    private final Duration timeout;
    private final Supplier<String> idSupplier;

    @Autowired
    public RpcDispatcher(OmnichannelProperties props) {
        this(props.rpc().timeout(), () -> UUID.randomUUID().toString());
    }

    RpcDispatcher(Duration timeout, Supplier<String> idSupplier) {
        this.timeout = timeout;
        this.idSupplier = idSupplier;
    }

    public CompletableFuture<JsonNode> call(DeviceSession device, String method, JsonNode params) {
        String id = idSupplier.get();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, new Pending(device.username(), future));
        future.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .whenComplete((result, error) -> pending.remove(id));
        try {
            device.send(Envelope.request(id, method, params));
        } catch (IOException e) {
            future.completeExceptionally(new RpcException(RpcException.SEND_FAILED, e.getMessage()));
        }
        return future;
    }

    /** @return true if a pending call matched this response id. */
    public boolean complete(Envelope response) {
        if (response.id() == null) {
            return false;
        }
        Pending p = pending.remove(response.id());
        if (p == null) {
            return false;
        }
        if (response.error() != null) {
            p.future().completeExceptionally(new RpcException(response.error().code(), response.error().message()));
        } else {
            p.future().complete(response.result());
        }
        return true;
    }

    /** Fails every pending call for a user, e.g. when its device disconnects. @return number failed. */
    public int failAll(String username, String code) {
        int count = 0;
        for (var entry : pending.entrySet()) {
            if (entry.getValue().username().equals(username) && pending.remove(entry.getKey()) != null) {
                entry.getValue().future().completeExceptionally(new RpcException(code, "device " + username + ": " + code));
                count++;
            }
        }
        return count;
    }

    public int pendingCount() {
        return pending.size();
    }
}
