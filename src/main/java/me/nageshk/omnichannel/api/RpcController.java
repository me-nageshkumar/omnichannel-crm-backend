package me.nageshk.omnichannel.api;

import tools.jackson.databind.JsonNode;
import me.nageshk.omnichannel.rpc.RpcDispatcher;
import me.nageshk.omnichannel.ws.DeviceRegistry;
import me.nageshk.omnichannel.ws.DeviceSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.concurrent.CompletableFuture;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Generic pass-through to any page method registered in the extension (getState, getInfo, ...).
 * Unlike /api/messages it does not require WhatsApp to be CONNECTED, so it doubles as a diagnostic.
 */
@RestController
@PreAuthorize("hasAuthority('SUPER_ADMIN')")
@RequestMapping("/api/phone-numbers/{phoneNumberId}/bridge")
public class RpcController {

    public record RpcRequest(@NotBlank String method, JsonNode params) {}

    private final DeviceRegistry registry;
    private final RpcDispatcher dispatcher;

    public RpcController(DeviceRegistry registry, RpcDispatcher dispatcher) {
        this.registry = registry;
        this.dispatcher = dispatcher;
    }

    @PostMapping("/rpc")
    public CompletableFuture<JsonNode> call(@Valid @RequestBody RpcRequest req, @PathVariable String phoneNumberId) {
        DeviceSession device = registry.find(phoneNumberId)
                .orElseThrow(() -> new NoDeviceException(NoDeviceException.NO_DEVICE,
                        "no extension connected for this number"));
        return dispatcher.call(device, req.method(), req.params());
    }
}
