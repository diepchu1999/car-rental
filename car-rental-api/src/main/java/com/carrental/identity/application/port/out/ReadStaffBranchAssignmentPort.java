package com.carrental.identity.application.port.out;

import com.carrental.identity.domain.StaffBranchAssignment;
import java.util.Optional;

/** Cổng đọc bản gán nội bộ theo BR-803; không suy vai hiện tại từ vai đã lưu. */
public interface ReadStaffBranchAssignmentPort {
    /** Trả rỗng khi tài khoản chưa có bản gán; lỗi lưu trữ phải đi ra ngoài. */
    Optional<StaffBranchAssignment> findByAccountCode(String accountCode);
}
