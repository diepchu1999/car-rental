package com.carrental.identity.adapter.out.persistence;

import com.carrental.PostgresTestConfiguration;
import com.carrental.identity.application.command.ResolveAccountCommand;
import com.carrental.identity.application.port.in.ResolveAccountUseCase;
import com.carrental.identity.domain.AccountRole;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Kiểm use case qua transaction Spring và PostgreSQL thật; đọc lại độc lập sau mỗi commit/rollback. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
class AccountResolutionIntegrationTest extends IdentityDatabaseTestSupport {
    @Autowired ResolveAccountUseCase resolve;

    /** Hai lượt đồng bộ khách giữ một mã và bằng chứng cũ dù claims mang phiên bản mới. */
    @Test
    void repeatedCustomerResolutionKeepsOneAccountAndOriginalConsent() {
        var identity = accountFixture(null);
        var first = resolve.resolve(ResolveAccountCommand.from(identity.keycloakSubject(), identity.phoneNumber().value(),
                Set.of(AccountRole.CUSTOMER), "local-test-v1", Instant.parse(AT)));
        var second = resolve.resolve(ResolveAccountCommand.from(identity.keycloakSubject(), identity.phoneNumber().value(),
                Set.of(AccountRole.CUSTOMER), "local-test-v2", Instant.parse(AT).plusSeconds(60)));
        assertEquals(first, second);
        assertTrue(first.code().matches("TK-[A-Z0-9]{6}"));
        var rows = jdbc.queryForList(sqlLoader.load("sql/identity/read_account_by_subject_for_test.sql"),
                Map.of("subject", identity.keycloakSubject()));
        assertEquals(1, rows.size());
        assertEquals(first.code(), rows.getFirst().get("code"));
        assertEquals("local-test-v1", rows.getFirst().get("terms_version"));
    }

    /** Nhân viên đăng ký thuê bổ sung consent, giữ nguyên mã và thời điểm tạo tài khoản. */
    @Test
    void employeeBecomesCustomerWithoutCreatingAnotherAccount() {
        var identity = accountFixture(null);
        var first = resolve.resolve(ResolveAccountCommand.from(identity.keycloakSubject(), identity.phoneNumber().value(),
                Set.of(AccountRole.BRANCH_STAFF), null, null));
        assertNull(first.termsVersion());
        String sql = sqlLoader.load("sql/identity/read_account_by_subject_for_test.sql");
        Map<String, ?> parameters = Map.of("subject", identity.keycloakSubject());
        OffsetDateTime createdAt = jdbc.queryForObject(sql, parameters,
                (rs, row) -> rs.getObject("created_at", OffsetDateTime.class));
        var customer = resolve.resolve(ResolveAccountCommand.from(identity.keycloakSubject(), identity.phoneNumber().value(),
                Set.of(AccountRole.BRANCH_STAFF, AccountRole.CUSTOMER), "local-test-v1", Instant.parse(AT)));
        assertEquals(first.code(), customer.code());
        assertEquals("local-test-v1", customer.termsVersion());
        assertEquals(Instant.parse(AT), customer.consentedAt());
        jdbc.queryForObject(sql, parameters, (rs, row) -> {
            assertEquals(createdAt, rs.getObject("created_at", OffsetDateTime.class));
            assertEquals(customer.termsVersion(), rs.getString("terms_version"));
            assertEquals(customer.consentedAt(), rs.getObject("consented_at", OffsetDateTime.class).toInstant());
            return true;
        });
    }

    /** Cùng subject nhưng đổi số bị từ chối, không bổ sung consent hoặc thay danh tính đã commit. */
    @Test
    void changedPhoneRollsBackWithoutAddingConsent() {
        var original = accountFixture(null);
        var other = accountFixture(null);
        var first = resolve.resolve(ResolveAccountCommand.from(original.keycloakSubject(), original.phoneNumber().value(),
                Set.of(AccountRole.ADMIN), null, null));
        var failure = assertThrowsExactly(DomainException.class, () -> resolve.resolve(ResolveAccountCommand.from(
                original.keycloakSubject(), other.phoneNumber().value(), Set.of(AccountRole.CUSTOMER), "local-test-v1", Instant.parse(AT))));
        assertEquals(ErrorCode.ACCOUNT_IDENTITY_MISMATCH, failure.errorCode());
        jdbc.queryForObject(sqlLoader.load("sql/identity/read_account_by_subject_for_test.sql"),
                Map.of("subject", original.keycloakSubject()), (rs, row) -> {
                    assertEquals(first.code(), rs.getString("code"));
                    assertEquals(original.phoneNumber().value(), rs.getString("phone_number"));
                    assertNull(rs.getString("terms_version"));
                    assertNull(rs.getObject("consented_at"));
                    return true;
                });
    }

    /** Số đã thuộc subject khác gây xung đột đúng mã, không tạo tài khoản thứ hai hoặc hợp nhất ngầm. */
    @Test
    void anotherSubjectCannotClaimExistingPhone() {
        var original = accountFixture(null);
        var other = accountFixture(null);
        resolve.resolve(ResolveAccountCommand.from(original.keycloakSubject(), original.phoneNumber().value(),
                Set.of(AccountRole.ADMIN), null, null));
        var failure = assertThrowsExactly(DomainException.class, () -> resolve.resolve(ResolveAccountCommand.from(
                other.keycloakSubject(), original.phoneNumber().value(), Set.of(AccountRole.ADMIN), null, null)));
        assertEquals(ErrorCode.ACCOUNT_IDENTITY_CONFLICT, failure.errorCode());
        assertEquals(DomainException.Category.CONFLICT, failure.category());
        assertTrue(jdbc.queryForList(sqlLoader.load("sql/identity/read_account_by_subject_for_test.sql"),
                Map.of("subject", other.keycloakSubject())).isEmpty());
    }
}
