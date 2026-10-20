package me.nageshk.omnichannel.api;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import me.nageshk.omnichannel.ws.DeviceRegistry;
import me.nageshk.omnichannel.ws.DeviceSession;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@PreAuthorize("hasAuthority('SUPER_ADMIN')")
@RequestMapping("/api/phone-numbers/{phoneNumberId}/bridge")
public class StatusController {

    public record StatusResponse(boolean deviceConnected, String waState, JsonNode info) {}

    private final DeviceRegistry registry;
    private final ObjectMapper mapper;

    public StatusController(DeviceRegistry registry, ObjectMapper mapper) {
        this.registry = registry;
        this.mapper = mapper;
    }

    @GetMapping("/status")
    public StatusResponse status(@PathVariable String phoneNumberId) {
        return registry.find(phoneNumberId)
                .map(d -> new StatusResponse(true, d.waState(), d.info()))
                .orElseGet(() -> new StatusResponse(false, DeviceSession.STATE_UNKNOWN, mapper.createObjectNode()));
    }
}
