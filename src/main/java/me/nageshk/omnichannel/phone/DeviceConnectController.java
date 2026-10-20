package me.nageshk.omnichannel.phone;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The extension swaps a connect code for its device key. No authentication; rate-limited per client IP. */
@RestController
@RequestMapping("/api/devices")
public class DeviceConnectController {

    public record ConnectRequest(String code) {}

    private final DeviceKeys keys;

    public DeviceConnectController(DeviceKeys keys) {
        this.keys = keys;
    }

    @PostMapping("/connect")
    public DeviceKeys.Connected connect(@RequestBody ConnectRequest req, HttpServletRequest request) {
        return keys.redeem(req.code(), request.getRemoteAddr());
    }
}
