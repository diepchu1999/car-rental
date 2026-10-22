package com.carrental.identity.domain;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** Kiểm bằng chứng BR-120 đủ cả cặp và không bị thay bằng cấu hình hoặc thời gian hiện tại. */
class TermsConsentTest {

    /** Giữ nguyên phiên bản và thời điểm, kể cả phiên bản cũ. */
    @Test
    void preservesOriginalEvidenceExactly() {
        Instant at = Instant.parse("2026-01-01T03:04:05Z");
        var consent = new TermsConsent("local-test-v1", at);
        assertEquals("local-test-v1", consent.version());
        assertEquals(at, consent.consentedAt());
    }

    /** Phiên bản thiếu hoặc trắng không được tự chữa bằng phiên bản mới. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void rejectsMissingVersion(String version) {
        var failure = assertThrowsExactly(DomainException.class,
                () -> new TermsConsent(version, Instant.parse("2026-01-01T00:00:00Z")));
        assertEquals(ErrorCode.CONSENT_REQUIRED, failure.errorCode());
        assertEquals(DomainException.Category.FORBIDDEN, failure.category());
    }

    /** Thiếu thời điểm trả đúng lỗi, không tự lấy now làm bằng chứng. */
    @Test
    void rejectsMissingTimestamp() {
        var failure = assertThrowsExactly(DomainException.class, () -> new TermsConsent("v1", null));
        assertEquals(ErrorCode.CONSENT_REQUIRED, failure.errorCode());
        assertEquals(DomainException.Category.FORBIDDEN, failure.category());
    }
}
