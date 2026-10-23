package me.nageshk.omnichannel.app.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.concurrent.CompletableFuture;
import me.nageshk.omnichannel.common.api.NoDeviceException;
import me.nageshk.omnichannel.service.rpc.RpcDispatcher;
import me.nageshk.omnichannel.service.ws.DeviceRegistry;
import me.nageshk.omnichannel.service.ws.DeviceSession;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@RestController
@PreAuthorize("hasAuthority('SUPER_ADMIN')")
@RequestMapping("/api/phone-numbers/{phoneNumberId}/bridge")
public class MessagesController {

    public record SendMessageRequest(
            @NotBlank String chatId,
            @NotBlank String content,
            JsonNode options,
            Boolean sendSeen) {}

    private final DeviceRegistry registry;
    private final RpcDispatcher dispatcher;
    private final ObjectMapper mapper;

    public MessagesController(DeviceRegistry registry, RpcDispatcher dispatcher, ObjectMapper mapper) {
        this.registry = registry;
        this.dispatcher = dispatcher;
        this.mapper = mapper;
    }

    @PostMapping("/messages")
    public CompletableFuture<JsonNode> send(@Valid @RequestBody SendMessageRequest req, @PathVariable String phoneNumberId) {
        DeviceSession device = requireReadyDevice(phoneNumberId);
        ObjectNode params = mapper.createObjectNode();
        params.put("chatId", req.chatId());
        params.put("content", req.content());
        params.set("options", req.options() == null || req.options().isNull() ? mapper.createObjectNode() : req.options());
        params.put("sendSeen", Boolean.TRUE.equals(req.sendSeen()));
        return dispatcher.call(device, "sendMessage", params);
    }

    private DeviceSession requireReadyDevice(String username) {
        DeviceSession device = registry.find(username)
                .orElseThrow(() -> new NoDeviceException(NoDeviceException.NO_DEVICE,
                        "no extension connected for user " + username));
        if (!device.confirmed() || !DeviceSession.STATE_CONNECTED.equals(device.waState())) {
            throw new NoDeviceException(NoDeviceException.WHATSAPP_NOT_READY,
                    "WhatsApp state is " + device.waState());
        }
        return device;
    }
}
