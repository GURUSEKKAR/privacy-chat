package com.secretchat.config;

import com.secretchat.common.RateLimiter;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class CoreConfig {

  /** Trusted server time (UTC). Tests replace it with a controllable clock. */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  /** Argon2id at the OWASP minimum profile: m=19 MiB, t=2, p=1. */
  @Bean
  PasswordEncoder passwordEncoder() {
    return new Argon2PasswordEncoder(16, 32, 1, 19 * 1024, 2);
  }

  @Bean
  RateLimiter loginFailureLimiter(AppProperties props, Clock clock) {
    return new RateLimiter(props.loginFailuresPerWindow(), props.loginWindow(), clock);
  }

  @Bean
  RateLimiter registrationLimiter(AppProperties props, Clock clock) {
    return new RateLimiter(props.registrationsPerWindow(), props.loginWindow(), clock);
  }

  @Bean
  RateLimiter sendLimiter(AppProperties props, Clock clock) {
    return new RateLimiter(props.sendsPerWindow(), props.sendWindow(), clock);
  }
}
