package com.secretchat.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Resolves {@code Authorization: Bearer <token>} into a {@link PrincipalAuthentication}. */
public class TokenAuthenticationFilter extends OncePerRequestFilter {
  private final AuthService authService;

  public TokenAuthenticationFilter(AuthService authService) {
    this.authService = authService;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String header = request.getHeader("Authorization");
    if (header != null && header.startsWith("Bearer ") && header.length() < 200) {
      authService
          .authenticate(header.substring(7).trim())
          .ifPresent(p ->
              SecurityContextHolder.getContext().setAuthentication(new PrincipalAuthentication(p)));
    }
    chain.doFilter(request, response);
  }
}
