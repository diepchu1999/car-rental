package com.carrental.identity.application.query;

import com.carrental.identity.domain.AccountRoles;
import com.carrental.shared.validation.Validations;

/**
 * Đầu vào nội bộ để dựng người gọi theo BR-803/809, không phải dữ liệu body/query của HTTP.
 * Biên xác thực lấy accountCode từ kết quả ResolveAccountUseCase và roles từ JWT đã xác minh.
 * Không nhận chi nhánh từ token hoặc client; phạm vi chi nhánh phải được đọc ở use case.
 *
 * @param accountCode mã tài khoản đã đồng bộ thành công
 * @param roles tập vai hiện tại đã xác thực, không phải vai đã lưu lúc gán chi nhánh
 */
public record GetCallerQuery(String accountCode, AccountRoles roles) {
    /** Kiểm dữ liệu bắt buộc, không tự cấp vai hoặc sửa mã tài khoản. */
    public GetCallerQuery {
        accountCode = Validations.requiredText(accountCode, "accountCode");
        roles = Validations.required(roles, "roles");
    }

    /** Tạo query nội bộ sau khi đồng bộ tài khoản; không xác minh JWT trong application. */
    public static GetCallerQuery from(String accountCode, AccountRoles roles) {
        return new GetCallerQuery(accountCode, roles);
    }

    /** Không tự in tập vai hay thông tin phạm vi vào log. */
    @Override
    public String toString() { return "GetCallerQuery[redacted]"; }
}
