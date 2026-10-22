package com.carrental.identity.application.service;

import com.carrental.identity.api.CallerRef;
import com.carrental.identity.application.port.in.GetCallerUseCase;
import com.carrental.identity.application.port.out.ReadStaffBranchAssignmentPort;
import com.carrental.identity.application.query.GetCallerQuery;
import com.carrental.identity.domain.AccountRole;
import com.carrental.shared.validation.Validations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.stream.Collectors;

/**
 * BR-803/809: ghép vai đã xác thực với phạm vi chi nhánh lưu tại identity, không tự gán vai.
 * Bước đồng bộ tài khoản đã hoàn tất trước khi vào use case chỉ đọc này; không bọc thao tác
 * tạo tài khoản bằng transaction read-only. Không giữ caller hoặc cache bản gán trên singleton.
 */
@Service
class CallerQueryService implements GetCallerUseCase {
    private final ReadStaffBranchAssignmentPort assignments;

    /** Chỉ nhận cổng đọc bản gán; không phụ thuộc HTTP, JWT, Keycloak SDK hay adapter. */
    CallerQueryService(ReadStaffBranchAssignmentPort assignments) {
        this.assignments = assignments;
    }

    /**
     * Chỉ bốn vai thuộc chi nhánh mới dùng bản gán; vai cấp công ty/khách không kế thừa bản gán cũ.
     * Có CUSTOMER kèm một vai nhân viên vẫn giữ cả hai vai, không mặc định quyền ADMIN.
     */
    @Override
    @Transactional(readOnly = true)
    public CallerRef get(GetCallerQuery query) {
        Validations.required(query, "query");
        Long branchId = null;
        if (query.roles().employeeRole().filter(AccountRole::isBranchScoped).isPresent()) {
            var assignment = assignments.findByAccountCode(query.accountCode());
            if (assignment == null) {
                throw new IllegalStateException("Staff branch lookup returned no result container.");
            }
            if (assignment.isPresent()) {
                var stored = assignment.get();
                if (!query.accountCode().equals(stored.accountCode())) {
                    throw new IllegalStateException("Staff branch lookup returned a different account.");
                }
                stored.requireCurrentRole(query.roles());
                branchId = stored.branchId();
            }
        }
        return new CallerRef(query.accountCode(), query.roles().values().stream()
                .map(AccountRole::name).collect(Collectors.toUnmodifiableSet()), branchId);
    }
}
