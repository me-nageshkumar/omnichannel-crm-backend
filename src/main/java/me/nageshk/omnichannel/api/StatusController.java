package me.nageshk.omnichannel.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import me.nageshk.omnichannel.ws.DeviceRegistry;
import me.nageshk.omnichannel.ws.DeviceSession;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class StatusController {

    public record StatusResponse(boolean deviceConnected, String waState, JsonNode info) {}

    private final DeviceRegistry registry;
    private final ObjectMapper mapper;

    public StatusController(DeviceRegistry registry, ObjectMapper mapper) {
        this.registry = registry;
        this.mapper = mapper;
    }

    @GetMapping("/status")
    public StatusResponse status(Authentication auth) {
        return registry.find(auth.getName())
                .map(d -> new StatusResponse(true, d.waState(), d.info()))
                .orElseGet(() -> new StatusResponse(false, DeviceSession.STATE_UNKNOWN, mapper.createObjectNode()));
    }
}
