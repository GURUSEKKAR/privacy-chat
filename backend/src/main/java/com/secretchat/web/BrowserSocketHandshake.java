package com.secretchat.web;

import com.secretchat.auth.PrincipalAuthentication;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

public class BrowserSocketHandshake implements HandshakeInterceptor {
  public static final String PRINCIPAL_ATTRIBUTE = "browser-approved-principal";
  private final BrowserSocketTickets tickets;

  public BrowserSocketHandshake(BrowserSocketTickets tickets) {
    this.tickets = tickets;
  }

  @Override
  public boolean beforeHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler handler,
      Map<String, Object> attributes) {
    if (request instanceof ServletServerHttpRequest servlet) {
      var principal = tickets.consume(servlet.getServletRequest().getParameter("ticket"));
      if (principal != null) {
        attributes.put(PRINCIPAL_ATTRIBUTE, new PrincipalAuthentication(principal));
        return true;
      }
    }
    response.setStatusCode(HttpStatus.FORBIDDEN);
    return false;
  }

  @Override
  public void afterHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler handler,
      Exception exception) {}
}
