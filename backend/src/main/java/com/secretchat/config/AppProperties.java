package com.secretchat.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Tunables. Defaults are conservative; override via environment variables. */
@ConfigurationProperties(prefix = "secretchat")
public record AppProperties(
    @DefaultValue("15m") Duration accessTokenTtl,
    @DefaultValue("7d") Duration refreshTokenTtl,
    @DefaultValue("2m") Duration challengeTtl,
    @DefaultValue("24h") Duration invitationTtl,
    @DefaultValue("7d") Duration keyPackageTtl,
    @DefaultValue("5") int loginFailuresPerWindow,
    @DefaultValue("5m") Duration loginWindow,
    @DefaultValue("40") int registrationsPerWindow,
    @DefaultValue("30") int sendsPerWindow,
    @DefaultValue("10s") Duration sendWindow,
    @DefaultValue("30") int maxRoomMembers,
    @DefaultValue("0s") Duration deletedMetadataRetention,
    @DefaultValue("100") int workerBatchSize,
    @DefaultValue("50000") int maxPendingChallenges) {}
