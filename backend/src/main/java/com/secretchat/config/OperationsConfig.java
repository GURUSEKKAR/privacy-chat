package com.secretchat.config;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class OperationsConfig {
  @Bean
  @Order(1)
  SecurityFilterChain operations(
      HttpSecurity http, @Value("${secretchat.metrics-token:}") String token) throws Exception {
    http.securityMatcher("/actuator/metrics", "/actuator/metrics/**", "/actuator/prometheus")
        .csrf(c -> c.disable())
        .httpBasic(c -> c.disable())
        .formLogin(c -> c.disable())
        .addFilterBefore(
            new OncePerRequestFilter() {
              @Override
              protected void doFilterInternal(
                  HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                  throws IOException, ServletException {
                String supplied = request.getHeader("Authorization");
                if (token.length() < 32
                    || supplied == null
                    || !MessageDigest.isEqual(
                        ("Bearer " + token).getBytes(StandardCharsets.UTF_8),
                        supplied.getBytes(StandardCharsets.UTF_8))) {
                  response.sendError(401);
                  return;
                }
                chain.doFilter(request, response);
              }
            },
            AnonymousAuthenticationFilter.class)
        .authorizeHttpRequests(a -> a.anyRequest().permitAll());
    return http.build();
  }

  @Bean
  FilterRegistrationBean<OncePerRequestFilter> authenticationMetrics(MeterRegistry registry) {
    var filter =
        new OncePerRequestFilter() {
          @Override
          protected void doFilterInternal(
              HttpServletRequest request, HttpServletResponse response, FilterChain chain)
              throws IOException, ServletException {
            try {
              chain.doFilter(request, response);
            } finally {
              if (request.getRequestURI().startsWith("/api/v1/auth/")
                  && (response.getStatus() == 401 || response.getStatus() == 429)) {
                registry.counter("secretchat.authentication.failures").increment();
              }
            }
          }
        };
    var registration = new FilterRegistrationBean<OncePerRequestFilter>(filter);
    registration.setOrder(-200);
    return registration;
  }
}
