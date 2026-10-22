package com.carrental.keycloak.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Metadata mở rộng model Keycloak; thao tác OTP dùng SQL trên cùng connection, không dùng repository JPA. */
@Entity(name = "CarRentalOtpChallenge")
@Table(name = "challenge", schema = "car_rental_otp")
public class OtpChallengeEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "challenge_id", nullable = false, length = 36, unique = true)
    private String challengeId;
    @Column(name = "scope_key", nullable = false, length = 64)
    private String scopeKey;
    @Column(name = "phone_key", nullable = false, length = 64)
    private String phoneKey;
    @Column(name = "code_digest", nullable = false, length = 64)
    private String codeDigest;
    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;
    @Column(nullable = false, length = 16)
    private String state;
    @Column(name = "consumed_at")
    private Instant consumedAt;

    /** Constructor dành cho Hibernate của Keycloak. */
    protected OtpChallengeEntity() { }
}
