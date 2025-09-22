package medi.ai.mediAi_backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * WebSocket + STOMP configuration.
 * Broker prefix is intentionally set to "/medicalReportTopic" (user requested).
 * Client will connect to /ws and subscribe to /medicalReportTopic/{userId}.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws") // client connects here
                .setAllowedOriginPatterns("http://localhost:3000", "https://tiwarivarun28.github.io")
                .withSockJS(); // fallback for older browsers
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // outgoing destinations (the broker) - we've chosen the custom prefix the user asked for
        config.enableSimpleBroker("/medicalReportTopic");
        // incoming messages from clients (if you want them) should be prefixed with /app
        config.setApplicationDestinationPrefixes("/app");
    }
}
