package com.carrental.identity.adapter.in.rest.admin;

import com.carrental.identity.adapter.in.rest.admin.request.AssignStaffBranchRequest;
import com.carrental.identity.adapter.in.rest.admin.response.StaffBranchAssignmentResponse;
import com.carrental.identity.adapter.in.rest.response.CurrentAccountResponse;
import com.carrental.identity.application.command.AssignStaffBranchCommand;
import com.carrental.identity.application.port.in.AssignStaffBranchUseCase;
import com.carrental.identity.application.port.in.GetCurrentAccountUseCase;
import com.carrental.shared.api.ApiResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** BR-803/807/809: nhân viên đọc chính mình, ADMIN gán chi nhánh; không có API tra người khác. */
@RestController
@RequestMapping(path = AdminAccountController.BASE_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
class AdminAccountController {
    static final String BASE_PATH = "/api/v1/admin/accounts";
    private final GetCurrentAccountUseCase accounts;
    private final AssignStaffBranchUseCase assignments;

    /** Nhận port/in, không đọc token, tra Keycloak hoặc kiểm quyền trong controller. */
    AdminAccountController(GetCurrentAccountUseCase accounts, AssignStaffBranchUseCase assignments) {
        this.accounts = accounts;
        this.assignments = assignments;
    }

    /** Trả mã TK-<6> để người dùng cung cấp cho ADMIN gán chi nhánh, không lộ ID hoặc subject nội bộ. */
    @GetMapping("/me")
    public ApiResponse<CurrentAccountResponse> getCurrent() {
        return ApiResponse.success(CurrentAccountResponse.fromDomain(accounts.get()));
    }

    /** Gán hoặc thay bản gán duy nhất; quyền ADMIN và vai đích do use case kiểm, response luôn là bản đọc lại. */
    @PutMapping(path = "/{accountCode}/branch", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<StaffBranchAssignmentResponse> assignBranch(@PathVariable("accountCode") String accountCode,
                                                                  @RequestBody AssignStaffBranchRequest request) {
        var result = assignments.assign(AssignStaffBranchCommand.from(accountCode, request.branchCode()));
        return ApiResponse.success(StaffBranchAssignmentResponse.fromDomain(result));
    }
}
