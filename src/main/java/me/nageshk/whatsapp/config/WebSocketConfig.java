package me.nageshk.whatsapp.config;

import me.nageshk.whatsapp.ws.BridgeWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final BridgeWebSocketHandler handler;
    private final WabridgeProperties props;

    public WebSocketConfig(BridgeWebSocketHandler handler, WabridgeProperties props) {
        this.handler = handler;
        this.props = props;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Hardening (post-PoC roadmap): pin to chrome-extension://<published extension id>
        registry.addHandler(handler, "/ws")
                .setAllowedOriginPatterns(props.ws().allowedOrigins().split("\\s*,\\s*"));
    }
}
