package com.carrental.keycloak.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Metadata hàng khoá theo số/IP, dùng chung giữa mọi phiên và nút Keycloak. */
@Entity(name = "CarRentalOtpSubjectLock")
@Table(name = "subject_lock", schema = "car_rental_otp")
public class OtpSubjectLockEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "subject_key", nullable = false, length = 64, unique = true)
    private String subjectKey;

    /** Constructor dành cho Hibernate của Keycloak. */
    protected OtpSubjectLockEntity() { }
}
