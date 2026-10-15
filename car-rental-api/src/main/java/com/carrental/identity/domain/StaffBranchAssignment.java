package com.carrental.identity.domain;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.shared.validation.Validations;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * Bản gán một nhân viên vào một chi nhánh theo BR-803/809, không phải nguồn cấp vai.
 * Vai hiện tại do Keycloak cung cấp; vai lưu tại đây chỉ dùng phát hiện bản gán đã lệch.
 * ID chi nhánh chỉ dùng nội bộ theo ADR-0008, không trả thẳng record này qua REST.
 * Tính duy nhất của nhân viên và tối đa một quản lý theo BR-807 do CSDL bảo vệ.
 *
 * @param accountCode mã tài khoản nghiệp vụ TK-<6>
 * @param branchId định danh chi nhánh nội bộ, đã được application tra qua branch.api
 * @param staffRole vai thuộc chi nhánh tại thời điểm gán
 * @param assignedAt thời điểm gán do application cấp từ đồng hồ dùng chung
 */
public record StaffBranchAssignment(String accountCode, long branchId, AccountRole staffRole, Instant assignedAt) {
    private static final Pattern ACCOUNT_CODE_PATTERN = Pattern.compile("TK-[A-Z0-9]{6}");

    /** Kiểm cấu trúc và phạm vi vai; không tự tra CSDL, tự cấp vai hoặc kiểm quyền ADMIN. */
    public StaffBranchAssignment {
        accountCode = Validations.requiredText(accountCode, "accountCode");
        if (!ACCOUNT_CODE_PATTERN.matcher(accountCode).matches()) {
            throw DomainException.invalidInput("accountCode must contain TK- followed by six uppercase ASCII letters or digits.");
        }
        if (branchId <= 0) {
            throw DomainException.invalidInput("branchId must be positive.");
        }
        staffRole = Validations.required(staffRole, "staffRole");
        if (!staffRole.isBranchScoped()) {
            throw DomainException.ruleViolation(ErrorCode.STAFF_ROLE_NOT_BRANCH_SCOPED);
        }
        assignedAt = Validations.required(assignedAt, "assignedAt");
    }

    /**
     * Kiểm vai hiện tại trước khi sử dụng bản gán để xác định phạm vi chi nhánh (BR-803, ADR-0006).
     * Không lấy staffRole đã lưu để bổ sung quyền vào tập vai hiện tại. CUSTOMER đi kèm cùng vai
     * nhân viên được chấp nhận theo BR-809; vai nhân viên thiếu hoặc khác gây STAFF_ROLE_MISMATCH.
     * Chỉ gọi khi cần dùng bản gán: người có vai cấp công ty không cần dùng bản gán cũ của họ.
     * Việc chưa có bản gán và quyền thao tác cụ thể được kiểm riêng ở application.
     *
     * @param currentRoles tập vai đã xác thực của chính tài khoản này
     * @throws DomainException nếu thiếu tập vai hoặc vai nhân viên không khớp
     */
    public void requireCurrentRole(AccountRoles currentRoles) {
        Validations.required(currentRoles, "currentRoles");
        if (currentRoles.employeeRole().filter(role -> role == staffRole).isEmpty()) {
            throw DomainException.forbidden(ErrorCode.STAFF_ROLE_MISMATCH);
        }
    }
}
