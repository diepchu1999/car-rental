package com.carrental.identity.application.service;

import com.carrental.branch.api.BranchDirectory;
import com.carrental.identity.api.CallerDirectory;
import com.carrental.identity.application.command.AssignStaffBranchCommand;
import com.carrental.identity.application.port.in.AssignStaffBranchUseCase;
import com.carrental.identity.application.port.out.ReadAccountPort;
import com.carrental.identity.application.port.out.ReadAccountRolesPort;
import com.carrental.identity.application.port.out.ReadStaffBranchAssignmentPort;
import com.carrental.identity.application.port.out.WriteStaffBranchAssignmentPort;
import com.carrental.identity.application.view.StaffBranchAssignmentDetail;
import com.carrental.identity.domain.AccountRole;
import com.carrental.identity.domain.StaffBranchAssignment;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.shared.validation.Validations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * BR-803/807/809: kiểm quyền ngay ở use case; không dùng bản gán cũ hay payload làm nguồn vai.
 * UNIQUE tại CSDL quyết định quản lý thứ hai; không đọc-rồi-ghi để chiếm chỗ quản lý.
 */
@Service
class StaffBranchAssignmentCommandService implements AssignStaffBranchUseCase {
    private final CallerDirectory callers;
    private final ReadAccountPort accounts;
    private final ReadAccountRolesPort roles;
    private final BranchDirectory branches;
    private final WriteStaffBranchAssignmentPort write;
    private final ReadStaffBranchAssignmentPort read;
    private final Clock clock;

    /** Nhận các cổng, không import HTTP/Security, Keycloak SDK, adapter hoặc JDBC vào application. */
    StaffBranchAssignmentCommandService(CallerDirectory callers, ReadAccountPort accounts, ReadAccountRolesPort roles,
                                        BranchDirectory branches, WriteStaffBranchAssignmentPort write,
                                        ReadStaffBranchAssignmentPort read, Clock clock) {
        this.callers = callers;
        this.accounts = accounts;
        this.roles = roles;
        this.branches = branches;
        this.write = write;
        this.read = read;
        this.clock = clock;
    }

    /**
     * Chặn người không phải ADMIN trước mọi tra cứu đích; lỗi ghi phải thoát ra để transaction rollback.
     * Vai đích lấy từ Keycloak theo subject của tài khoản, chi nhánh tra qua branch.api (ADR-0008).
     * Ghi và đọc lại cùng transaction; chỉ trả mã nghiệp vụ, không trả aggregate hoặc ID số.
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public StaffBranchAssignmentDetail assign(AssignStaffBranchCommand command) {
        if (!callers.current().hasRole(AccountRole.ADMIN.name())) {
            throw DomainException.forbidden(ErrorCode.ACCESS_DENIED);
        }
        Validations.required(command, "command");
        var account = accounts.findByCode(command.accountCode())
                .orElseThrow(() -> DomainException.notFound(ErrorCode.ACCOUNT_NOT_FOUND));
        var currentRoles = roles.findBySubject(account.keycloakSubject())
                .orElseThrow(() -> DomainException.notFound(ErrorCode.ACCOUNT_NOT_FOUND));
        var staffRole = currentRoles.employeeRole().filter(AccountRole::isBranchScoped)
                .orElseThrow(() -> DomainException.ruleViolation(ErrorCode.STAFF_ROLE_NOT_BRANCH_SCOPED));
        var branch = branches.findByCode(command.branchCode())
                .orElseThrow(() -> DomainException.notFound(ErrorCode.BRANCH_NOT_FOUND));
        var assignment = new StaffBranchAssignment(account.code(), branch.id(), staffRole, clock.instant());
        if (!write.save(assignment)) {
            throw DomainException.notFound(ErrorCode.ACCOUNT_NOT_FOUND);
        }
        var stored = read.findByAccountCode(account.code())
                .orElseThrow(() -> new IllegalStateException("Saved staff branch assignment could not be reloaded."));
        if (!stored.accountCode().equals(account.code()) || stored.branchId() != branch.id()
                || stored.staffRole() != staffRole) {
            throw new IllegalStateException("Reloaded staff branch assignment does not match the saved identity and scope.");
        }
        var savedBranch = branches.findById(stored.branchId())
                .orElseThrow(() -> new IllegalStateException("Assigned branch could not be resolved."));
        return new StaffBranchAssignmentDetail(stored.accountCode(), savedBranch.code(),
                stored.staffRole().name(), stored.assignedAt());
    }
}
