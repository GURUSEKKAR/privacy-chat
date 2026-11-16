package com.secretchat.common;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.secretchat.auth.ChallengeService;
import com.secretchat.config.AppProperties;
import com.secretchat.testsupport.MutableClock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class BoundedAuthenticationStateTest {
  @Test
  void limiterBoundsDistinctKeysAndRecoversAfterWindow() {
    var clock = new MutableClock();
    var limiter = new RateLimiter(2, Duration.ofMinutes(1), clock, 2);
    assertThat(limiter.tryAcquire("a")).isTrue();
    assertThat(limiter.tryAcquire("b")).isTrue();
    assertThat(limiter.tryAcquire("c")).isFalse();
    assertThat(limiter.isBlocked("c")).isTrue();
    assertThat(limiter.tryAcquire("a")).isTrue();
    for (int i = 0; i < 100; i++) assertThat(limiter.tryAcquire("a")).isFalse();
    clock.advance(Duration.ofMinutes(1));
    assertThat(limiter.tryAcquire("c")).isTrue();
    assertThat(limiter.isBlocked("a")).isFalse();
  }

  @Test
  void challengeCapacityIsReleasedByConsumptionAndExpiry() {
    var clock = new MutableClock();
    var props = mock(AppProperties.class);
    when(props.challengeTtl()).thenReturn(Duration.ofMinutes(1));
    when(props.maxPendingChallenges()).thenReturn(2);
    var challenges = new ChallengeService(clock, props);
    var first = challenges.issue();
    challenges.issue();
    assertThatThrownBy(challenges::issue).isInstanceOf(ApiException.class);
    assertThat(challenges.consume(first.challengeId())).isPresent();
    assertThat(challenges.consume(first.challengeId())).isEmpty();
    challenges.issue();
    clock.advance(Duration.ofMinutes(1));
    assertThat(challenges.issue()).isNotNull();
  }
}
