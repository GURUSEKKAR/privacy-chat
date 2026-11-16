package com.secretchat.config;

import com.secretchat.auth.AuthService;
import com.secretchat.auth.PrincipalAuthentication;
import com.secretchat.auth.TokenAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

/**
 * Stateless bearer-token security. CSRF is disabled because no cookies/ambient credentials are
 * used. Room, invitation and WebSocket access additionally require an APPROVED device.
 */
@Configuration
public class SecurityConfig {

  @Bean
  org.springframework.web.cors.CorsConfigurationSource corsConfigurationSource(
      @org.springframework.beans.factory.annotation.Value("${secretchat.web.allowed-origins:}")
          String allowedOrigins) {
    var cors = new org.springframework.web.cors.CorsConfiguration();
    cors.setAllowedOrigins(
        java.util.Arrays.stream(allowedOrigins.split(","))
            .map(String::trim)
            .filter(origin -> !origin.isEmpty())
            .toList());
    cors.setAllowedMethods(java.util.List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
    cors.setAllowedHeaders(java.util.List.of("Authorization", "Content-Type"));
    cors.setAllowCredentials(false);
    cors.setMaxAge(600L);
    var source = new org.springframework.web.cors.UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/api/**", cors);
    return source;
  }

  /** Bearer-only authentication has no default password account or generated-password log. */
  @Bean
  org.springframework.security.core.userdetails.UserDetailsService noPasswordUsers() {
    return username -> {
      throw new org.springframework.security.core.userdetails.UsernameNotFoundException(
          "Bearer authentication required");
    };
  }

  @Bean
  @org.springframework.core.annotation.Order(2)
  SecurityFilterChain api(HttpSecurity http, AuthService authService) throws Exception {
    http.csrf(c -> c.disable())
        .cors(Customizer.withDefaults())
        .httpBasic(b -> b.disable())
        .formLogin(f -> f.disable())
        .logout(l -> l.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .headers(h -> h.contentSecurityPolicy(c -> c.policyDirectives("default-src 'none'")))
        .addFilterBefore(
            new TokenAuthenticationFilter(authService), AnonymousAuthenticationFilter.class)
        .exceptionHandling(e ->
            e.authenticationEntryPoint(
                    (req, res, ex) -> write(res, 401, "UNAUTHENTICATED", "Authentication required"))
                .accessDeniedHandler((req, res, ex) ->
                    write(res, 403, "DEVICE_NOT_APPROVED", "Approved device required")))
        .authorizeHttpRequests(a ->
            a.requestMatchers(
                    HttpMethod.POST,
                    "/api/v1/auth/challenge",
                    "/api/v1/auth/register",
                    "/api/v1/auth/login",
                    "/api/v1/auth/refresh")
                .permitAll()
                .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**")
                .permitAll()
                .requestMatchers("/error")
                .permitAll()
                .requestMatchers("/api/v1/auth/mfa", "/api/v1/auth/mfa/**", "/api/v1/auth/logout")
                .authenticated()
                .requestMatchers("/api/v1/devices/**")
                .authenticated()
                .requestMatchers(HttpMethod.GET, "/ws/browser")
                .permitAll()
                .requestMatchers("/api/v1/ws-ticket")
                .hasAuthority(PrincipalAuthentication.DEVICE_APPROVED)
                .requestMatchers("/api/v1/rooms/**", "/api/v1/invites/**", "/ws", "/ws/**")
                .hasAuthority(PrincipalAuthentication.DEVICE_APPROVED)
                .anyRequest()
                .denyAll());
    return http.build();
  }

  private static void write(
      jakarta.servlet.http.HttpServletResponse res, int status, String code, String msg)
      throws java.io.IOException {
    res.setStatus(status);
    res.setContentType(MediaType.APPLICATION_JSON_VALUE);
    res.getWriter()
        .write("{\"status\":" + status + ",\"code\":\"" + code + "\",\"message\":\"" + msg + "\"}");
  }
}
