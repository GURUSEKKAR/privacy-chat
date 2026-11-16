package com.secretchat.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/** Static browser assets only. API authorization remains in its separate bearer-only chain. */
@Configuration
public class WebSecurityConfig {
  @Bean
  @Order(1)
  SecurityFilterChain webAssets(HttpSecurity http) throws Exception {
    http.securityMatcher("/", "/index.html", "/favicon.svg", "/assets/**")
        .csrf(c -> c.disable())
        .httpBasic(b -> b.disable())
        .formLogin(f -> f.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .headers(h ->
            h.addHeaderWriter((request, response) -> {
              String host = request.getHeader("Host");
              String sockets =
                  host != null && host.matches("[a-zA-Z0-9.\\[\\]:-]+")
                      ? " wss://" + host + " ws://" + host
                      : "";
              response.setHeader(
                  "Content-Security-Policy",
                  "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'"
                      + sockets
                      + "; base-uri 'none'; form-action 'self'; frame-ancestors 'none'");
              response.setHeader("Cache-Control", "no-store");
            }))
        .authorizeHttpRequests(a ->
            a.requestMatchers(HttpMethod.GET, "/", "/index.html", "/favicon.svg", "/assets/**")
                .permitAll()
                .anyRequest()
                .denyAll());
    return http.build();
  }
}
