package com.carrental.identity.adapter.out.persistence;

import com.carrental.PostgresTestConfiguration;
import com.carrental.identity.application.port.out.ReadStaffBranchAssignmentPort;
import com.carrental.identity.application.port.out.WriteStaffBranchAssignmentPort;
import com.carrental.identity.domain.AccountRole;
import com.carrental.identity.domain.StaffBranchAssignment;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Kiểm SQL thật của bản gán; không có transaction bao test để đọc độc lập sau commit/rollback.
 * Branch ID giả có chủ đích: adapter không kiểm tồn tại chi nhánh, đó là việc của use case (ADR-0008).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
class StaffBranchAssignmentPersistenceIntegrationTest extends IdentityDatabaseTestSupport {
    private static final Instant ASSIGNED_AT = Instant.parse(AT);
    @Autowired WriteStaffBranchAssignmentPort write;
    @Autowired ReadStaffBranchAssignmentPort read;
    @Autowired PlatformTransactionManager transactionManager;

    /** Lưu và khôi phục đủ trường cho từng vai thuộc chi nhánh, không dùng ID tài khoản ở hợp đồng port. */
    @ParameterizedTest
    @EnumSource(value = AccountRole.class, names = {"BRANCH_STAFF", "BRANCH_MANAGER", "DELIVERY_STAFF", "DRIVER"})
    void roundTripsEveryBranchScopedRole(AccountRole role) {
        var assignment = new StaffBranchAssignment(createAccount(), 935000L + role.ordinal(), role, ASSIGNED_AT);
        assertTrue(saveInTransaction(assignment));
        assertEquals(assignment, read.findByAccountCode(assignment.accountCode()).orElseThrow());
    }

    /** Tài khoản thiếu không được tự tạo và không tạo bản gán mồ côi; có tài khoản nhưng chưa gán cũng trả rỗng. */
    @Test
    void missingAccountDoesNotCreateAnAssignment() {
        String missing = accountFixture(null).code();
        assertFalse(saveInTransaction(new StaffBranchAssignment(missing, 935010L, AccountRole.BRANCH_STAFF, ASSIGNED_AT)));
        assertTrue(read.findByAccountCode(missing).isEmpty());
        assertTrue(read.findByAccountCode(createAccount()).isEmpty());
    }

    /** Cập nhật cùng tài khoản giữ một dòng và cùng ID bản gán, không để lại dòng ở chi nhánh cũ. */
    @Test
    void updatingAssignmentKeepsOneRowPerAccount() {
        String code = createAccount();
        var first = new StaffBranchAssignment(code, 935020L, AccountRole.BRANCH_STAFF, ASSIGNED_AT);
        assertTrue(saveInTransaction(first));
        var originalRows = rowsForBranches(List.of(935020L, 935021L));
        assertEquals(1, originalRows.size());
        var changed = new StaffBranchAssignment(code, 935021L, AccountRole.DELIVERY_STAFF, ASSIGNED_AT.plusSeconds(60));
        assertTrue(saveInTransaction(changed));
        assertEquals(changed, read.findByAccountCode(code).orElseThrow());
        var finalRows = rowsForBranches(List.of(935020L, 935021L));
        assertEquals(1, finalRows.size());
        assertEquals(originalRows.getFirst().get("id"), finalRows.getFirst().get("id"));
        assertEquals(935021L, finalRows.getFirst().get("branch_id"));
    }

    /** Gọi lại cùng bản gán không nhân đôi dòng hoặc báo trùng quản lý của chính mình. */
    @Test
    void savingTheSameManagerAgainDoesNotConflict() {
        var assignment = new StaffBranchAssignment(createAccount(), 935030L, AccountRole.BRANCH_MANAGER, ASSIGNED_AT);
        assertTrue(saveInTransaction(assignment));
        assertTrue(saveInTransaction(assignment));
        assertEquals(assignment, read.findByAccountCode(assignment.accountCode()).orElseThrow());
        assertEquals(1, rowsForBranches(List.of(935030L)).size());
    }

    /** Quản lý thứ hai bị đúng lỗi 409 và không có bản gán sau rollback; quản lý cũ không bị thay. */
    @Test
    void secondManagerIsRejectedWithoutReplacingTheExistingManager() {
        var first = new StaffBranchAssignment(createAccount(), 935040L, AccountRole.BRANCH_MANAGER, ASSIGNED_AT);
        var second = new StaffBranchAssignment(createAccount(), 935040L, AccountRole.BRANCH_MANAGER, ASSIGNED_AT);
        assertTrue(saveInTransaction(first));
        assertManagerConflict(() -> saveInTransaction(second));
        assertEquals(first, read.findByAccountCode(first.accountCode()).orElseThrow());
        assertTrue(read.findByAccountCode(second.accountCode()).isEmpty());
        assertEquals(1, rowsForBranches(List.of(935040L)).size());
    }

    /** UPDATE gây trùng quản lý phải rollback về nguyên bản gán cũ, không chỉ bảo vệ INSERT. */
    @Test
    void conflictingUpdatePreservesTheOriginalAssignment() {
        var manager = new StaffBranchAssignment(createAccount(), 935050L, AccountRole.BRANCH_MANAGER, ASSIGNED_AT);
        var employee = new StaffBranchAssignment(createAccount(), 935051L, AccountRole.BRANCH_STAFF, ASSIGNED_AT);
        assertTrue(saveInTransaction(manager));
        assertTrue(saveInTransaction(employee));
        var conflict = new StaffBranchAssignment(employee.accountCode(), manager.branchId(), AccountRole.BRANCH_MANAGER,
                ASSIGNED_AT.plusSeconds(60));
        assertManagerConflict(() -> saveInTransaction(conflict));
        assertEquals(manager, read.findByAccountCode(manager.accountCode()).orElseThrow());
        assertEquals(employee, read.findByAccountCode(employee.accountCode()).orElseThrow());
    }

    /** Index riêng quản lý không chặn nhiều nhân viên cùng làm ở một chi nhánh. */
    @Test
    void multipleStaffCanShareTheSameBranch() {
        var first = new StaffBranchAssignment(createAccount(), 935060L, AccountRole.BRANCH_STAFF, ASSIGNED_AT);
        var second = new StaffBranchAssignment(createAccount(), 935060L, AccountRole.DELIVERY_STAFF, ASSIGNED_AT);
        assertTrue(saveInTransaction(first));
        assertTrue(saveInTransaction(second));
        assertEquals(first, read.findByAccountCode(first.accountCode()).orElseThrow());
        assertEquals(second, read.findByAccountCode(second.accountCode()).orElseThrow());
        assertEquals(2, rowsForBranches(List.of(935060L)).size());
    }

    /** Ghi tài khoản giả duy nhất bằng fixture SQL, trả mã dùng ở biên port. */
    private String createAccount() {
        var parameters = accountParameters();
        insertAccount(parameters);
        return (String) parameters.getValue("code");
    }

    /** Adapter không quản lý transaction; test dựng ranh giới tương đương application service. */
    private boolean saveInTransaction(StaffBranchAssignment assignment) {
        return Boolean.TRUE.equals(new TransactionTemplate(transactionManager).execute(status -> write.save(assignment)));
    }

    /** Đọc độc lập sau commit/rollback để kiểm số dòng, định danh và chi nhánh thật trong CSDL. */
    private List<Map<String, Object>> rowsForBranches(List<Long> branches) {
        return jdbc.queryForList(sqlLoader.load("sql/identity/read_assignment_rows_for_test.sql"), Map.of("branchIds", branches));
    }

    /** Không chấp nhận lỗi JDBC chung thay cho lỗi nghiệp vụ đã duyệt. */
    private static void assertManagerConflict(org.junit.jupiter.api.function.Executable action) {
        var failure = assertThrowsExactly(DomainException.class, action);
        assertEquals(ErrorCode.BRANCH_MANAGER_ALREADY_ASSIGNED, failure.errorCode());
        assertEquals(DomainException.Category.CONFLICT, failure.category());
    }
}
