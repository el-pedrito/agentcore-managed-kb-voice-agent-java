package com.example.techagent.voice;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** Registers the browser voice endpoint. Only the demo screen (demo-ui, port 8090) may connect. */
@Configuration
@EnableWebSocket
@Profile("voice-web")
class VoiceWebConfig implements WebSocketConfigurer {

    private final VoiceWebSocketHandler handler;
    private final String[] origins;

    VoiceWebConfig(VoiceWebSocketHandler handler, @Value("${voice.allowed-origins}") String[] origins) {
        this.handler = handler;
        this.origins = origins;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/voice").setAllowedOrigins(origins);
    }
}
