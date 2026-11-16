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

  public WebSocketConfig(StompAuthInterceptor authInterceptor, WsSessionRegistry registry) {
    this.authInterceptor = authInterceptor;
    this.registry = registry;
  }

  @Override
  public void registerStompEndpoints(StompEndpointRegistry endpoints) {
    endpoints.addEndpoint("/ws");
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
