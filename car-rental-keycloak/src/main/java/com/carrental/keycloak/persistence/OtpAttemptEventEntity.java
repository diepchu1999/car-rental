package com.carrental.keycloak.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Metadata lịch sử cửa sổ trượt BR-129; không có số điện thoại/IP bản rõ. */
@Entity(name = "CarRentalOtpAttemptEvent")
@Table(name = "attempt_event", schema = "car_rental_otp")
public class OtpAttemptEventEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "phone_key", nullable = false, length = 64)
    private String phoneKey;
    @Column(name = "ip_key", nullable = false, length = 64)
    private String ipKey;
    @Column(nullable = false, length = 8)
    private String kind;
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    /** Constructor dành cho Hibernate của Keycloak. */
    protected OtpAttemptEventEntity() { }
}
