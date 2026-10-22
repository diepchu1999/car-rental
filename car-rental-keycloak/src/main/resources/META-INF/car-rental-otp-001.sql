-- Kho kỹ thuật thuộc Keycloak, chỉ được migration bởi chính kết nối Keycloak đang dùng.
CREATE SCHEMA car_rental_otp;
CREATE TABLE car_rental_otp.subject_lock (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    subject_key VARCHAR(64) NOT NULL UNIQUE
);
CREATE TABLE car_rental_otp.challenge (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    challenge_id VARCHAR(36) NOT NULL UNIQUE,
    scope_key VARCHAR(64) NOT NULL,
    phone_key VARCHAR(64) NOT NULL,
    code_digest VARCHAR(64) NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    failed_attempts INTEGER NOT NULL,
    state VARCHAR(16) NOT NULL,
    consumed_at TIMESTAMPTZ,
    CONSTRAINT chk_otp_lifetime CHECK (expires_at > issued_at),
    CONSTRAINT chk_otp_failures CHECK (failed_attempts >= 0),
    CONSTRAINT chk_otp_state CHECK (state IN ('ACTIVE','CONSUMED','SUPERSEDED','EXHAUSTED')),
    CONSTRAINT chk_otp_consumption CHECK ((state = 'CONSUMED') = (consumed_at IS NOT NULL))
);
CREATE INDEX idx_otp_challenge_phone ON car_rental_otp.challenge(phone_key);
CREATE TABLE car_rental_otp.attempt_event (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    phone_key VARCHAR(64) NOT NULL,
    ip_key VARCHAR(64) NOT NULL,
    kind VARCHAR(8) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_otp_event_kind CHECK (kind IN ('SENT','FAILED'))
);
CREATE INDEX idx_otp_event_phone_time ON car_rental_otp.attempt_event(phone_key, occurred_at);
CREATE INDEX idx_otp_event_ip_time ON car_rental_otp.attempt_event(ip_key, occurred_at);
