package com.parkride.parking.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Configures STOMP over WebSocket for real-time availability updates.
 *
 * <p>Client connection flow:
 * <ol>
 *   <li>Client connects to {@code ws://host:8082/ws} (or {@code /ws} with SockJS fallback)</li>
 *   <li>Client subscribes to {@code /topic/availability/{lotId}}</li>
 *   <li>After each booking/cancel, server sends to that topic</li>
 * </ol>
 *
 * <p>Frontend JS example:
 * <pre>
 * const client = new Client({ brokerURL: 'ws://localhost:8082/ws' });
 * client.onConnect = () => {
 *   client.subscribe('/topic/availability/LOT_UUID', msg => {
 *     const { availableSlots } = JSON.parse(msg.body);
 *     updateMap(availableSlots);
 *   });
 * };
 * client.activate();
 * </pre>
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Simple in-memory broker for /topic destinations
        registry.enableSimpleBroker("/topic");
        // Prefix for messages bound to @MessageMapping methods (server-side handlers)
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("http://localhost:*", "https://*.parkride.com")
                .withSockJS(); // SockJS fallback for browsers without native WebSocket
    }
}
