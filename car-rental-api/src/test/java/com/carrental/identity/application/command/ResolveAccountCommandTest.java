package com.carrental.identity.application.command;

import com.carrental.identity.domain.AccountRole;
import com.carrental.identity.domain.AccountRoles;
import com.carrental.identity.domain.VietnamMobileNumber;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Kiểm đầu vào use case trước khi ghi CSDL theo BR-120/809, không giả vờ xác minh JWT. */
class ResolveAccountCommandTest {
    private static final Instant AT = Instant.parse("2026-10-13T03:00:00Z");

    /** Số nội địa được chuẩn hoá; bằng chứng giữ đúng giá trị từ nguồn danh tính. */
    @Test
    void normalizesPhoneAndPreservesConsent() {
        var command = ResolveAccountCommand.from("subject", "0912345678", Set.of(AccountRole.CUSTOMER), "local-test-v1", AT);
        assertEquals("+84912345678", command.phoneNumber().value());
        assertEquals("subject", command.keycloakSubject());
        assertEquals("local-test-v1", command.consent().version());
        assertEquals(AT, command.consent().consentedAt());
    }

    /** Nhân viên không bị ép đăng ký thuê; CUSTOMER thiếu hoặc thiếu một nửa bằng chứng phải bị từ chối. */
    @Test
    void enforcesConsentWithoutInventingEvidence() {
        assertNull(ResolveAccountCommand.from("subject", "0912345678", Set.of(AccountRole.ADMIN), null, null).consent());
        assertConsentFailure(() -> ResolveAccountCommand.from("subject", "0912345678", Set.of(AccountRole.CUSTOMER), null, null));
        assertConsentFailure(() -> ResolveAccountCommand.from("subject", "0912345678", Set.of(AccountRole.ADMIN), "v1", null));
        assertConsentFailure(() -> ResolveAccountCommand.from("subject", "0912345678", Set.of(AccountRole.ADMIN), null, AT));
        assertConsentFailure(() -> new ResolveAccountCommand("subject", new VietnamMobileNumber("0912345678"),
                new AccountRoles(Set.of(AccountRole.CUSTOMER)), null));
    }

    /** Constructor trực tiếp cũng không cho bỏ qua các trường bắt buộc. */
    @Test
    void rejectsMissingIdentityAndRoles() {
        var phone = new VietnamMobileNumber("0912345678");
        var roles = new AccountRoles(Set.of(AccountRole.ADMIN));
        for (String subject : new String[]{null, "", " "}) {
            assertInvalid(() -> new ResolveAccountCommand(subject, phone, roles, null));
        }
        assertInvalid(() -> new ResolveAccountCommand("subject", null, roles, null));
        assertInvalid(() -> new ResolveAccountCommand("subject", phone, null, null));
    }

    /** Biểu diễn command không làm rò rỉ định danh qua log. */
    @Test
    void redactsCommandRepresentation() {
        assertEquals("ResolveAccountCommand[redacted]", ResolveAccountCommand.from("subject", "0912345678",
                Set.of(AccountRole.ADMIN), null, null).toString());
    }

    /** Lỗi phải đúng nhóm từ chối và mã consent, không xanh nhờ lỗi null bất ngờ. */
    private static void assertConsentFailure(org.junit.jupiter.api.function.Executable action) {
        var failure = assertThrowsExactly(DomainException.class, action);
        assertEquals(ErrorCode.CONSENT_REQUIRED, failure.errorCode());
        assertEquals(DomainException.Category.FORBIDDEN, failure.category());
    }

    /** Thiếu định danh là lỗi đầu vào của command, không tự tạo giá trị giả. */
    private static void assertInvalid(org.junit.jupiter.api.function.Executable action) {
        assertEquals(ErrorCode.INVALID_REQUEST, assertThrowsExactly(DomainException.class, action).errorCode());
    }
}
