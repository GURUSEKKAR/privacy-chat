package com.secretchat.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * STOMP over WebSocket at {@code /ws}. The TLS profile serves WSS directly; the handshake requires
 * a bearer token of an approved device.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
  private final StompAuthInterceptor authInterceptor;
  private final WsSessionRegistry registry;
  private final com.secretchat.web.BrowserSocketTickets browserTickets;
  private final String browserOrigins;

  public WebSocketConfig(
      StompAuthInterceptor authInterceptor,
      WsSessionRegistry registry,
      com.secretchat.web.BrowserSocketTickets browserTickets,
      @org.springframework.beans.factory.annotation.Value("${secretchat.web.allowed-origins:}")
          String browserOrigins) {
    this.authInterceptor = authInterceptor;
    this.registry = registry;
    this.browserTickets = browserTickets;
    this.browserOrigins = browserOrigins;
  }

  @Override
  public void registerStompEndpoints(StompEndpointRegistry endpoints) {
    endpoints.addEndpoint("/ws");
    var browserAllowedOrigins =
        new java.util.ArrayList<>(
            java.util.List.of(
                "http://localhost:5173", "http://127.0.0.1:5173", "https://localhost:18443"));
    java.util.Arrays.stream(browserOrigins.split(","))
        .map(String::trim)
        .filter(origin -> !origin.isEmpty())
        .forEach(browserAllowedOrigins::add);
    endpoints
        .addEndpoint("/ws/browser")
        .setAllowedOrigins(browserAllowedOrigins.toArray(String[]::new))
        .addInterceptors(new com.secretchat.web.BrowserSocketHandshake(browserTickets))
        .setHandshakeHandler(
            new org.springframework.web.socket.server.support.DefaultHandshakeHandler() {
              @Override
              protected java.security.Principal determineUser(
                  org.springframework.http.server.ServerHttpRequest request,
                  org.springframework.web.socket.WebSocketHandler handler,
                  java.util.Map<String, Object> attributes) {
                return (java.security.Principal)
                    attributes.get(com.secretchat.web.BrowserSocketHandshake.PRINCIPAL_ATTRIBUTE);
              }
            });
  }

  @Override
  public void configureMessageBroker(MessageBrokerRegistry broker) {
    broker.enableSimpleBroker("/queue");
    broker.setApplicationDestinationPrefixes("/app");
    broker.setUserDestinationPrefix("/user");
  }

  @Override
  public void configureClientInboundChannel(ChannelRegistration registration) {
    registration.interceptors(authInterceptor);
  }

  @Override
  public void configureWebSocketTransport(WebSocketTransportRegistration transport) {
    transport.addDecoratorFactory(registry);
    transport.setMessageSizeLimit(128 * 1024);
    transport.setSendBufferSizeLimit(1024 * 1024);
  }
}
