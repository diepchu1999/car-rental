package com.carrental.identity.application.port.out;

import com.carrental.identity.domain.StaffBranchAssignment;

/** Cổng lưu bản gán theo BR-803/807/809; quyền ADMIN và tra chi nhánh thuộc application. */
public interface WriteStaffBranchAssignmentPort {
    /**
     * Lưu một bản gán trên mỗi tài khoản: chèn nếu chưa có, cập nhật bản hiện hữu nếu đã có.
     * Không tự tạo tài khoản. Trả false chỉ khi mã tài khoản không tồn tại.
     * Trùng quản lý của chi nhánh gây BRANCH_MANAGER_ALREADY_ASSIGNED, không lấy chỗ của người cũ.
     * Application sở hữu transaction và phải để lỗi thoát ra để rollback, không tiếp tục ghi sau lỗi.
     * Kết quả true chưa đồng nghĩa đã commit; application phải đọc lại trước khi trả view.
     */
    boolean save(StaffBranchAssignment assignment);
}
