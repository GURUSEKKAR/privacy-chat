package com.secretchat.testsupport;

import java.time.Clock;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Real PostgreSQL via Testcontainers (shared per JVM) and a controllable clock. */
@TestConfiguration(proxyBeanMethods = false)
public class TestInfra {
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  static {
    POSTGRES.start();
  }

  @Bean
  @ServiceConnection
  PostgreSQLContainer postgres() {
    return POSTGRES;
  }

  @Bean
  MutableClock mutableClock() {
    return new MutableClock();
  }

  @Bean
  @Primary
  Clock testClock(MutableClock c) {
    return c;
  }
}
