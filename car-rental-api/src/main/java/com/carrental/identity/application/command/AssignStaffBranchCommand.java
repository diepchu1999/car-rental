package com.carrental.identity.application.command;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.validation.Validations;

/**
 * Yêu cầu gán chi nhánh theo BR-803/809, chỉ chứa mã nghiệp vụ.
 * Không nhận vai hoặc người thực hiện từ payload; application lấy từ nguồn danh tính tin cậy.
 */
public record AssignStaffBranchCommand(String accountCode, String branchCode) {
    /** Kiểm mã đúng định dạng, không cắt khoảng trắng hoặc đổi chữ hoa để sửa đầu vào. */
    public AssignStaffBranchCommand {
        accountCode = Validations.requiredText(accountCode, "accountCode");
        branchCode = Validations.requiredText(branchCode, "branchCode");
        if (!accountCode.matches("TK-[A-Z0-9]{6}")) {
            throw DomainException.invalidInput("accountCode must be a valid account business code.");
        }
        if (!branchCode.matches("CN-[A-Z0-9]{6}")) {
            throw DomainException.invalidInput("branchCode must be a valid branch business code.");
        }
    }

    /** Factory nhận giá trị thô, không phụ thuộc request DTO hoặc framework web. */
    public static AssignStaffBranchCommand from(String accountCode, String branchCode) {
        return new AssignStaffBranchCommand(accountCode, branchCode);
    }
}
