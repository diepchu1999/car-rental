package com.carrental.identity.adapter.out.persistence;

import com.carrental.PostgresTestConfiguration;
import com.carrental.identity.application.port.out.ReadAccountPort;
import com.carrental.identity.application.port.out.WriteAccountPort;
import com.carrental.identity.domain.Account;
import com.carrental.identity.domain.TermsConsent;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;

import static com.carrental.identity.application.port.out.WriteAccountPort.InsertResult.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Kiểm adapter thật qua PostgreSQL, cùng transaction rollback của từng test; không mock cổng. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
@Transactional
class AccountPersistenceIntegrationTest extends IdentityDatabaseTestSupport {
    private static final Instant CREATED_AT = Instant.parse(AT);
    private static final TermsConsent CONSENT = new TermsConsent("local-test-v1", CREATED_AT.minusSeconds(60));
    @Autowired WriteAccountPort write;
    @Autowired ReadAccountPort read;

    /** Ghi/đọc đủ dữ liệu của khách và nhân viên; không tự thêm consent cho người chưa đăng ký thuê. */
    @Test
    void roundTripsAccountWithAndWithoutConsent() {
        for (TermsConsent consent : new TermsConsent[]{null, CONSENT}) {
            Account account = accountFixture(consent);
            assertEquals(INSERTED, write.insert(account, CREATED_AT));
            assertAccount(account, read.findBySubject(account.keycloakSubject()).orElseThrow());
            assertAccount(account, read.findByCode(account.code()).orElseThrow());
        }
    }

    /** Chỉ thực sự không có tài khoản mới trả Optional rỗng. */
    @Test
    void missingIdentityReturnsEmpty() {
        Account missing = accountFixture(null);
        assertTrue(read.findByCode(missing.code()).isEmpty());
        assertTrue(read.findBySubject(missing.keycloakSubject()).isEmpty());
    }

    /** Trùng subject không thay mã, số, consent hay created_at của bản ghi đầu tiên. */
    @Test
    void existingSubjectPreservesOriginalAccount() {
        Account original = accountFixture(CONSENT);
        assertEquals(INSERTED, write.insert(original, CREATED_AT));
        Account other = accountFixture(new TermsConsent("local-test-v2", CREATED_AT));
        Account repeated = Account.restore(other.code(), original.keycloakSubject(), other.phoneNumber(), other.consent().orElseThrow());
        assertEquals(SUBJECT_EXISTS, write.insert(repeated, CREATED_AT.plusSeconds(3600)));
        assertAccount(original, read.findBySubject(original.keycloakSubject()).orElseThrow());
        assertTrue(read.findByCode(other.code()).isEmpty());
        OffsetDateTime storedCreation = jdbc.queryForObject(sqlLoader.load("sql/identity/read_account_by_subject_for_test.sql"),
                Map.of("subject", original.keycloakSubject()), (rs, row) -> rs.getObject("created_at", OffsetDateTime.class));
        assertEquals(CREATED_AT, storedCreation.toInstant());
    }

    /** Trùng mã được phân biệt và không làm hỏng transaction; đổi mã rồi chèn lại thành công. */
    @Test
    void codeCollisionCanRetryInsideTheSameTransaction() {
        Account original = accountFixture(null);
        Account next = accountFixture(null);
        assertEquals(INSERTED, write.insert(original, CREATED_AT));
        Account colliding = Account.restore(original.code(), next.keycloakSubject(), next.phoneNumber(), null);
        assertEquals(CODE_COLLISION, write.insert(colliding, CREATED_AT));
        assertEquals(INSERTED, write.insert(next, CREATED_AT));
        assertAccount(original, read.findByCode(original.code()).orElseThrow());
        assertAccount(next, read.findByCode(next.code()).orElseThrow());
    }

    /** Subject khác nhưng cùng số trả đúng ACCOUNT_IDENTITY_CONFLICT, không giả thành thử lại mã. */
    @Test
    void anotherSubjectWithTheSamePhoneIsAnIdentityConflict() {
        Account original = accountFixture(null);
        Account next = accountFixture(null);
        assertEquals(INSERTED, write.insert(original, CREATED_AT));
        Account colliding = Account.restore(next.code(), next.keycloakSubject(), original.phoneNumber(), null);
        var failure = assertThrowsExactly(DomainException.class, () -> write.insert(colliding, CREATED_AT));
        assertEquals(ErrorCode.ACCOUNT_IDENTITY_CONFLICT, failure.errorCode());
        assertEquals(DomainException.Category.CONFLICT, failure.category());
        assertEquals(ErrorCode.ACCOUNT_IDENTITY_CONFLICT.defaultMessage(), failure.getMessage());
        assertTrue(read.findBySubject(next.keycloakSubject()).isEmpty());
        assertAccount(original, read.findBySubject(original.keycloakSubject()).orElseThrow());
    }

    /** Trùng cả mã lẫn số với subject khác vẫn là lỗi danh tính, không vòng lặp sinh mã vô ích. */
    @Test
    void phoneConflictTakesPrecedenceOverCodeCollision() {
        Account original = accountFixture(null);
        Account next = accountFixture(null);
        assertEquals(INSERTED, write.insert(original, CREATED_AT));
        var failure = assertThrowsExactly(DomainException.class, () -> write.insert(
                Account.restore(original.code(), next.keycloakSubject(), original.phoneNumber(), null), CREATED_AT));
        assertEquals(ErrorCode.ACCOUNT_IDENTITY_CONFLICT, failure.errorCode());
    }

    /** Thêm consent một lần và đọc lại đúng cặp; các lần gọi sau không ghi đè phiên bản/thời điểm. */
    @Test
    void addsConsentOnlyOnce() {
        Account employee = accountFixture(null);
        assertEquals(INSERTED, write.insert(employee, CREATED_AT));
        Account first = Account.restore(employee.code(), employee.keycloakSubject(), employee.phoneNumber(), CONSENT);
        assertTrue(write.addConsentIfAbsent(first));
        Account later = Account.restore(employee.code(), employee.keycloakSubject(), employee.phoneNumber(),
                new TermsConsent("local-test-v2", CREATED_AT.plusSeconds(3600)));
        assertFalse(write.addConsentIfAbsent(later));
        assertAccount(first, read.findBySubject(employee.keycloakSubject()).orElseThrow());
    }

    /** Không dùng chỉ mã tài khoản để gắn bằng chứng từ subject hoặc số khác. */
    @Test
    void consentUpdateRequiresAllIdentityFieldsToMatch() {
        Account employee = accountFixture(null);
        Account other = accountFixture(null);
        assertEquals(INSERTED, write.insert(employee, CREATED_AT));
        assertFalse(write.addConsentIfAbsent(Account.restore(employee.code(), other.keycloakSubject(), employee.phoneNumber(), CONSENT)));
        assertFalse(write.addConsentIfAbsent(Account.restore(employee.code(), employee.keycloakSubject(), other.phoneNumber(), CONSENT)));
        assertFalse(write.addConsentIfAbsent(Account.restore(other.code(), employee.keycloakSubject(), employee.phoneNumber(), CONSENT)));
        assertTrue(read.findBySubject(employee.keycloakSubject()).orElseThrow().consent().isEmpty());
    }

    /** Không chấp nhận dùng phương thức bổ sung consent để xoá bằng chứng bằng null. */
    @Test
    void rejectsConsentWriteWithoutEvidence() {
        assertThrowsExactly(IllegalArgumentException.class, () -> write.addConsentIfAbsent(accountFixture(null)));
    }

    /** CHECK của CSDL vẫn làm test đỏ đúng tên, không bị DO NOTHING nuốt như xung đột UNIQUE. */
    @Test
    void doesNotSwallowOtherDatabaseConstraints() {
        Account valid = accountFixture(null);
        Account invalid = mock(Account.class);
        when(invalid.code()).thenReturn("INVALID");
        when(invalid.keycloakSubject()).thenReturn(valid.keycloakSubject());
        when(invalid.phoneNumber()).thenReturn(valid.phoneNumber());
        when(invalid.consent()).thenReturn(java.util.Optional.empty());
        assertConstraint(() -> write.insert(invalid, CREATED_AT), "23514", "account", "chk_account_code_format");
    }

    /** So từng trường của aggregate, không dựa vào equals hay toString đã che dữ liệu. */
    private static void assertAccount(Account expected, Account actual) {
        assertEquals(expected.code(), actual.code());
        assertEquals(expected.keycloakSubject(), actual.keycloakSubject());
        assertEquals(expected.phoneNumber(), actual.phoneNumber());
        assertEquals(expected.consent(), actual.consent());
    }
}
