package com.carrental.identity.application.port.in;

import com.carrental.identity.application.command.AssignStaffBranchCommand;
import com.carrental.identity.application.view.StaffBranchAssignmentDetail;

/** Cổng gán chi nhánh cho nhân viên; chỉ ADMIN được gọi theo BR-809. */
public interface AssignStaffBranchUseCase {
    /** Kiểm quyền, tra vai thật và chi nhánh, lưu nguyên tử rồi đọc lại; không tạo tài khoản hay đổi vai. */
    StaffBranchAssignmentDetail assign(AssignStaffBranchCommand command);
}
