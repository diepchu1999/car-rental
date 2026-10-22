package com.carrental.identity.domain;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Kiểm danh tính bất biến và bằng chứng đầu tiên của tài khoản theo BR-120/809, chưa kiểm CSDL. */
class AccountTest {
    private static final String SUBJECT = "test-keycloak-subject";
    private static final VietnamMobileNumber PHONE = new VietnamMobileNumber("0912345678");
    private static final AccountRoles CUSTOMER = new AccountRoles(Set.of(AccountRole.CUSTOMER));
    private static final AccountRoles STAFF = new AccountRoles(Set.of(AccountRole.BRANCH_STAFF));
    private static final TermsConsent CONSENT = new TermsConsent("local-test-v1", Instant.parse("2026-01-01T00:00:00Z"));

    /** Tạo khách giữ chính xác mã, subject, số đã chuẩn hoá và bằng chứng. */
    @Test
    void createsCustomerWithConsent() {
        Account account = Account.create("TK-ABC123", SUBJECT, PHONE, CUSTOMER, CONSENT);
        assertEquals("TK-ABC123", account.code());
        assertEquals(SUBJECT, account.keycloakSubject());
        assertEquals("+84912345678", account.phoneNumber().value());
        assertEquals(CONSENT, account.consent().orElseThrow());
    }

    /** Nhân viên chưa thuê không bị buộc đồng ý điều khoản khách; không tự tạo consent. */
    @Test
    void createsEmployeeWithoutCustomerConsent() {
        assertTrue(Account.create("TK-ABC123", SUBJECT, PHONE, STAFF, null).consent().isEmpty());
    }

    /** Khách, kể cả đồng thời là nhân viên, thiếu consent bị từ chối đúng 403/CONSENT_REQUIRED. */
    @Test
    void rejectsCustomerWithoutConsent() {
        for (AccountRoles roles : new AccountRoles[]{CUSTOMER,
                new AccountRoles(Set.of(AccountRole.CUSTOMER, AccountRole.ADMIN))}) {
            var failure = assertThrowsExactly(DomainException.class,
                    () -> Account.create("TK-ABC123", SUBJECT, PHONE, roles, null));
            assertForbidden(failure, ErrorCode.CONSENT_REQUIRED);
        }
    }

    /** Mã sai không được tự sửa chữ hoa hoặc cắt khoảng trắng trên cả đường tạo và khôi phục. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "TK-abc123", "TK-ABC12", "TK-ABC1234", "XE-ABC123", " TK-ABC123"})
    void rejectsInvalidBusinessCode(String code) {
        assertInvalid(() -> Account.create(code, SUBJECT, PHONE, CUSTOMER, CONSENT));
        assertInvalid(() -> Account.restore(code, SUBJECT, PHONE, CONSENT));
    }

    /** Định danh bắt buộc có trên cả hai đường; không dùng số hay subject giả thay dữ liệu thiếu. */
    @Test
    void rejectsMissingIdentityAndRoles() {
        for (String subject : new String[]{null, "", " "}) {
            assertInvalid(() -> Account.create("TK-ABC123", subject, PHONE, STAFF, null));
            assertInvalid(() -> Account.restore("TK-ABC123", subject, PHONE, null));
        }
        assertInvalid(() -> Account.create("TK-ABC123", SUBJECT, null, STAFF, null));
        assertInvalid(() -> Account.restore("TK-ABC123", SUBJECT, null, null));
        assertInvalid(() -> Account.create("TK-ABC123", SUBJECT, PHONE, null, null));
    }

    /** Subject là định danh opaque của Keycloak, không tự buộc định dạng UUID. */
    @Test
    void restoresIdentityAndOptionalOriginalConsent() {
        Account restored = Account.restore("TK-ABC123", SUBJECT, PHONE, CONSENT);
        assertEquals(SUBJECT, restored.keycloakSubject());
        assertEquals(CONSENT, restored.consent().orElseThrow());
        assertTrue(Account.restore("TK-ABC123", SUBJECT, PHONE, null).consent().isEmpty());
    }

    /** Cùng số ở dạng khác vẫn là cùng danh tính, không sinh đối tượng hay tài khoản mới. */
    @Test
    void acceptsEquivalentPhoneIdentity() {
        Account account = Account.create("TK-ABC123", SUBJECT, PHONE, CUSTOMER, CONSENT);
        assertSame(account, account.withAuthenticatedIdentity(SUBJECT,
                new VietnamMobileNumber("+84912345678"), CUSTOMER, CONSENT));
    }

    /** Số khác hoặc subject khác không được ghi đè âm thầm, kể cả dữ liệu mới bị thiếu. */
    @Test
    void rejectsChangedIdentityWithoutExposingValues() {
        Account account = Account.create("TK-ABC123", SUBJECT, PHONE, CUSTOMER, CONSENT);
        var changedPhone = assertThrowsExactly(DomainException.class, () -> account.withAuthenticatedIdentity(
                SUBJECT, new VietnamMobileNumber("0912345679"), CUSTOMER, CONSENT));
        var changedSubject = assertThrowsExactly(DomainException.class, () -> account.withAuthenticatedIdentity(
                "another-subject", PHONE, CUSTOMER, CONSENT));
        var missing = assertThrowsExactly(DomainException.class,
                () -> account.withAuthenticatedIdentity(null, null, CUSTOMER, CONSENT));
        for (DomainException failure : new DomainException[]{changedPhone, changedSubject, missing}) {
            assertForbidden(failure, ErrorCode.ACCOUNT_IDENTITY_MISMATCH);
            assertEquals(ErrorCode.ACCOUNT_IDENTITY_MISMATCH.defaultMessage(), failure.getMessage());
        }
        assertEquals(PHONE, account.phoneNumber());
        assertEquals(SUBJECT, account.keycloakSubject());
    }

    /** Nhân viên trở thành khách chỉ bổ sung consent, giữ nguyên tài khoản và đối tượng cũ. */
    @Test
    void addsFirstConsentWithoutChangingAccountIdentity() {
        Account employee = Account.create("TK-ABC123", SUBJECT, PHONE, STAFF, null);
        Account updated = employee.withAuthenticatedIdentity(SUBJECT, PHONE,
                new AccountRoles(Set.of(AccountRole.CUSTOMER, AccountRole.BRANCH_STAFF)), CONSENT);
        assertEquals(employee.code(), updated.code());
        assertEquals(employee.keycloakSubject(), updated.keycloakSubject());
        assertEquals(employee.phoneNumber(), updated.phoneNumber());
        assertEquals(CONSENT, updated.consent().orElseThrow());
        assertTrue(employee.consent().isEmpty());
    }

    /** Phiên bản mới hay lượt gọi bằng vai nhân viên không được xoá hoặc thay consent đã lưu. */
    @Test
    void neverReplacesOriginalConsent() {
        Account account = Account.restore("TK-ABC123", SUBJECT, PHONE, CONSENT);
        TermsConsent changed = new TermsConsent("local-test-v2", CONSENT.consentedAt().plusSeconds(60));
        assertSame(account, account.withAuthenticatedIdentity(SUBJECT, PHONE, CUSTOMER, changed));
        assertSame(account, account.withAuthenticatedIdentity(SUBJECT, PHONE, STAFF, null));
        assertEquals(CONSENT, account.consent().orElseThrow());
    }

    /** Có consent trong CSDL không hợp thức hoá token khách bị thiếu bằng chứng. */
    @Test
    void stillRequiresEvidenceForReturningCustomer() {
        Account account = Account.restore("TK-ABC123", SUBJECT, PHONE, CONSENT);
        var failure = assertThrowsExactly(DomainException.class,
                () -> account.withAuthenticatedIdentity(SUBJECT, PHONE, CUSTOMER, null));
        assertForbidden(failure, ErrorCode.CONSENT_REQUIRED);
    }

    /** Nối chuỗi tài khoản không làm lộ số điện thoại hoặc subject của Keycloak. */
    @Test
    void redactsSensitiveIdentityFromToString() {
        assertEquals("Account[code=TK-ABC123]",
                Account.create("TK-ABC123", SUBJECT, PHONE, CUSTOMER, CONSENT).toString());
    }

    /** Phải đúng lỗi đầu vào, không xanh nhờ một NullPointerException ngoài dự kiến. */
    private static void assertInvalid(org.junit.jupiter.api.function.Executable action) {
        var failure = assertThrowsExactly(DomainException.class, action);
        assertEquals(ErrorCode.INVALID_REQUEST, failure.errorCode());
        assertEquals(DomainException.Category.INVALID_INPUT, failure.category());
    }

    /** Kiểm cả mã lẫn nhóm để không vô tình trả 400 hoặc 500 thay cho từ chối truy cập. */
    private static void assertForbidden(DomainException failure, ErrorCode code) {
        assertEquals(code, failure.errorCode());
        assertEquals(DomainException.Category.FORBIDDEN, failure.category());
    }
}
