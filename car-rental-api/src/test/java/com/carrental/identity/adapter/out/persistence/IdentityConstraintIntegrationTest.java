package com.carrental.identity.adapter.out.persistence;

import com.carrental.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Kiểm ràng buộc V010 qua PostgreSQL thật; mỗi test rollback, SQL lỗi phải đúng nguyên nhân. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
@Transactional
class IdentityConstraintIntegrationTest extends IdentityDatabaseTestSupport {

    /** Các trường danh tính bắt buộc phải bị NOT NULL chặn khi ghi SQL trực tiếp. */
    @ParameterizedTest
    @CsvSource({"code,code", "subject,keycloak_subject", "phone,phone_number", "createdAt,created_at"})
    void rejectsMissingAccountField(String parameter, String column) {
        var parameters = accountParameters().addValue(parameter, null);
        assertNotNullColumn(() -> insertAccount(parameters), "account", column);
    }

    /** Gán chi nhánh không được thiếu tài khoản, chi nhánh, vai hoặc thời điểm gán. */
    @ParameterizedTest
    @CsvSource({"accountId,account_id", "branchId,branch_id", "role,staff_role", "assignedAt,assigned_at"})
    void rejectsMissingAssignmentField(String parameter, String column) {
        var parameters = assignmentParameters(newAccount(), 910008, "DRIVER").addValue(parameter, null);
        assertNotNullColumn(() -> insertAssignment(parameters), "staff_branch_assignment", column);
    }

    /** BR-120/809: nhân viên được thiếu cả cặp consent, khách có bằng chứng được lưu nguyên giá trị. */
    @Test
    void storesIdentityAndOptionalConsent() {
        for (boolean withConsent : new boolean[]{false, true}) {
            var parameters = accountParameters();
            if (withConsent) parameters.addValue("version", "local-test-v1").addValue("consentedAt", AT);
            long id = insertAccount(parameters);
            jdbc.queryForObject(sqlLoader.load("sql/identity/read_account_for_test.sql"), Map.of("id", id), (rs, row) -> {
                assertEquals(parameters.getValue("code"), rs.getString("code"));
                assertEquals(parameters.getValue("subject"), rs.getString("keycloak_subject"));
                assertEquals(parameters.getValue("phone"), rs.getString("phone_number"));
                assertEquals(parameters.getValue("version"), rs.getString("terms_version"));
                assertEquals(withConsent ? OffsetDateTime.parse(AT) : null, rs.getObject("consented_at", OffsetDateTime.class));
                assertEquals(OffsetDateTime.parse(AT), rs.getObject("created_at", OffsetDateTime.class));
                return id;
            });
        }
    }

    /** Mã, subject và số có UNIQUE độc lập; mỗi lần chỉ làm trùng một trường. */
    @ParameterizedTest
    @CsvSource({"code,uq_account_code", "subject,uq_account_keycloak_subject", "phone,uq_account_phone_number"})
    void rejectsDuplicateAccountIdentity(String field, String constraint) {
        var original = accountParameters();
        insertAccount(original);
        var duplicate = accountParameters().addValue(field, original.getValue(field));
        assertConstraint(() -> insertAccount(duplicate), "23505", "account", constraint);
    }

    /** Mã sai bị đúng CHECK định dạng chặn ngay cả khi bỏ qua domain. */
    @ParameterizedTest
    @ValueSource(strings = {"", "TK-abc123", "TK-ABC12", "XE-ABC123", "TK-ABC123\n"})
    void rejectsMalformedCode(String code) {
        var parameters = accountParameters().addValue("code", code);
        assertConstraint(() -> insertAccount(parameters), "23514", "account", "chk_account_code_format");
    }

    /** CSDL chỉ nhận dạng lưu chuẩn, không tự coi số nội địa hoặc quốc tế là một số mới. */
    @ParameterizedTest
    @ValueSource(strings = {"0912345678", "+14155552671", "+842838221234", "+84912345678\n", ""})
    void rejectsNonCanonicalPhone(String phone) {
        var parameters = accountParameters().addValue("phone", phone);
        assertConstraint(() -> insertAccount(parameters), "23514", "account", "chk_account_phone_format");
    }

    /** Subject rỗng hoặc chỉ khoảng trắng không phải định danh hợp lệ. */
    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t\n"})
    void rejectsBlankSubject(String subject) {
        var parameters = accountParameters().addValue("subject", subject);
        assertConstraint(() -> insertAccount(parameters), "23514", "account", "chk_account_subject_not_blank");
    }

    /** Cặp bằng chứng thiếu nửa, phiên bản trắng hoặc thời gian vô hạn đều bị CHECK chặn. */
    @ParameterizedTest
    @CsvSource(value = {"v1,NULL", "NULL,2026-10-13T03:00:00Z", "' ',2026-10-13T03:00:00Z", "v1,infinity"}, nullValues = "NULL")
    void rejectsIncompleteConsent(String version, String at) {
        var parameters = accountParameters().addValue("version", version).addValue("consentedAt", at);
        assertConstraint(() -> insertAccount(parameters), "23514", "account", "chk_account_consent");
    }

    /** Thời gian tạo phải hữu hạn, không nhận sentinel infinity của PostgreSQL. */
    @Test
    void rejectsInfiniteCreationTime() {
        var parameters = accountParameters().addValue("createdAt", "infinity");
        assertConstraint(() -> insertAccount(parameters), "23514", "account", "chk_account_created_at");
    }

    /** Tối đa một gán chi nhánh cho mỗi tài khoản, dù hai chi nhánh khác nhau. */
    @Test
    void rejectsSecondBranchForTheSameAccount() {
        long account = newAccount();
        insertAssignment(assignmentParameters(account, 910001, "BRANCH_STAFF"));
        assertConstraint(() -> insertAssignment(assignmentParameters(account, 910002, "BRANCH_STAFF")),
                "23505", "staff_branch_assignment", "uq_staff_branch_account");
    }

    /** BR-807: quản lý thứ hai bị UNIQUE index có điều kiện từ chối. */
    @Test
    void rejectsSecondManagerForTheSameBranch() {
        long first = newAccount();
        long second = newAccount();
        insertAssignment(assignmentParameters(first, 910003, "BRANCH_MANAGER"));
        assertConstraint(() -> insertAssignment(assignmentParameters(second, 910003, "BRANCH_MANAGER")),
                "23505", "staff_branch_assignment", "uq_staff_branch_manager");
    }

    /** Nhiều nhân viên và một quản lý cùng chi nhánh hợp lệ; index không cấm nhầm mọi bản ghi. */
    @Test
    void permitsMultipleStaffAndOneManagerInTheSameBranch() {
        for (String role : List.of("BRANCH_STAFF", "BRANCH_STAFF", "DELIVERY_STAFF", "DRIVER", "BRANCH_MANAGER")) {
            assertTrue(insertAssignment(assignmentParameters(newAccount(), 910004, role)) > 0);
        }
    }

    /** ADMIN và vai cấp công ty/ngoài công ty không được ghi vào bảng gán chi nhánh BR-803. */
    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "ACCOUNTANT", "OPERATIONS_MANAGER", "CUSTOMER", "HOST",
            "BUSINESS_PARTNER", "BUSINESS_PARTNER_STAFF", "FREELANCE_DRIVER", "UNKNOWN"})
    void rejectsNonBranchRole(String role) {
        long account = newAccount();
        assertConstraint(() -> insertAssignment(assignmentParameters(account, 910005, role)),
                "23514", "staff_branch_assignment", "chk_staff_branch_role");
    }

    /** ID chi nhánh phải dương nhưng không có FK chéo module. */
    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsNonPositiveBranch(long branch) {
        long account = newAccount();
        assertConstraint(() -> insertAssignment(assignmentParameters(account, branch, "DRIVER")),
                "23514", "staff_branch_assignment", "chk_staff_branch_id");
    }

    /** FK cùng identity chặn gán cho tài khoản chưa tồn tại. */
    @Test
    void rejectsMissingAccount() {
        assertConstraint(() -> insertAssignment(assignmentParameters(-1, 910006, "DRIVER")),
                "23503", "staff_branch_assignment", "fk_staff_branch_account");
    }

    /** Thời điểm gán không được dùng giá trị infinity để che mất thông tin. */
    @Test
    void rejectsInfiniteAssignmentTime() {
        var parameters = assignmentParameters(newAccount(), 910007, "DRIVER").addValue("assignedAt", "infinity");
        assertConstraint(() -> insertAssignment(parameters), "23514", "staff_branch_assignment", "chk_staff_branch_assigned_at");
    }

    /** ADR-0008: duy nhất FK account nội bộ, không có FK sang branch hoặc database Keycloak. */
    @Test
    void hasOnlyTheInternalAccountForeignKey() {
        var keys = jdbc.query(sqlLoader.load("sql/identity/read_foreign_keys_for_test.sql"), Map.of(),
                (rs, row) -> rs.getString("conname") + ":" + rs.getString("target_schema"));
        assertEquals(List.of("fk_staff_branch_account:identity"), keys);
    }
}
