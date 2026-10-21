package com.carrental.identity.adapter.in.rest.admin.response;

import com.carrental.identity.application.view.StaffBranchAssignmentDetail;

import java.time.Instant;
import java.util.Objects;

/** BR-803/807/809: kết quả gán đã đọc lại, chỉ có mã nghiệp vụ, không có ID số hoặc subject Keycloak. */
public record StaffBranchAssignmentResponse(String accountCode, String branchCode,
                                            String staffRole, Instant assignedAt) {
    /** Ánh xạ view sau khi lưu, không dùng lại payload làm kết quả giả. */
    public static StaffBranchAssignmentResponse fromDomain(StaffBranchAssignmentDetail view) {
        Objects.requireNonNull(view, "view must not be null.");
        return new StaffBranchAssignmentResponse(view.accountCode(), view.branchCode(),
                view.staffRole(), view.assignedAt());
    }

    /** Không tự in vai và phạm vi chi nhánh khi response bị nối vào log. */
    @Override
    public String toString() { return "StaffBranchAssignmentResponse[accountCode=" + accountCode + "]"; }
}
