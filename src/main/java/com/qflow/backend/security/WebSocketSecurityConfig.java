package com.qflow.backend.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.socket.EnableWebSocketSecurity;
import org.springframework.security.messaging.access.intercept.MessageMatcherDelegatingAuthorizationManager;

@Configuration
@EnableWebSocketSecurity
public class WebSocketSecurityConfig {

    @Bean
    public AuthorizationManager<Message<?>> messageAuthorizationManager(
            MessageMatcherDelegatingAuthorizationManager.Builder messages) {

        messages
                // Connection lifecycle messages (CONNECT is authenticated by StompJwtChannelInterceptor)
                .simpTypeMatchers(
                        SimpMessageType.CONNECT,
                        SimpMessageType.HEARTBEAT,
                        SimpMessageType.UNSUBSCRIBE,
                        SimpMessageType.DISCONNECT,
                        SimpMessageType.OTHER
                ).permitAll()
                // Client messages sent to application endpoints
                .simpDestMatchers("/app/**").authenticated()
                // User-specific and queue/place/service subscriptions
                .simpSubscribeDestMatchers("/user/**").authenticated()
                .simpSubscribeDestMatchers("/topic/queues", "/topic/queues/*").authenticated()
                .simpSubscribeDestMatchers("/topic/places/**", "/topic/services/**").authenticated()
                // Admin subscriptions
                .simpSubscribeDestMatchers("/topic/admin/**").hasRole("ADMIN")
                // Reject everything else
                .anyMessage().denyAll();

        return messages.build();
    }

    /**
     * {@code @EnableWebSocketSecurity} enforces a CSRF token on STOMP CONNECT frames by default.
     * This API authenticates WebSocket clients with a JWT in the CONNECT headers (not cookies),
     * so CSRF protection does not apply. Defining a bean with this name replaces the default
     * CSRF interceptor (equivalent to the old {@code sameOriginDisabled() = true}).
     */
    @Bean(name = "csrfChannelInterceptor")
    public ChannelInterceptor csrfChannelInterceptor() {
        return new ChannelInterceptor() {
        };
    }
}
