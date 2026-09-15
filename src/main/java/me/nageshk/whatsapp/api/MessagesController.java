package me.nageshk.whatsapp.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import me.nageshk.whatsapp.rpc.RpcDispatcher;
import me.nageshk.whatsapp.ws.DeviceRegistry;
import me.nageshk.whatsapp.ws.DeviceSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.concurrent.CompletableFuture;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
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
    public CompletableFuture<JsonNode> send(@Valid @RequestBody SendMessageRequest req, Authentication auth) {
        DeviceSession device = requireReadyDevice(auth.getName());
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
        if (!DeviceSession.STATE_CONNECTED.equals(device.waState())) {
            throw new NoDeviceException(NoDeviceException.WHATSAPP_NOT_READY,
                    "WhatsApp state is " + device.waState());
        }
        return device;
    }
}
