package com.carrental.identity.adapter.in.rest.response;

import com.carrental.identity.application.view.CurrentAccountDetail;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * Hợp đồng đọc chính mình dùng chung cho client/admin theo BR-120/803/809.
 * Không dùng response này cho việc đọc tài khoản người khác; không chứa ID số, subject hay token.
 * roles là tập vai của request hiện tại, không phải toàn bộ vai trên tài khoản Keycloak.
 */
public record CurrentAccountResponse(String code, String phoneNumber, Set<String> roles,
                                     String branchCode, String termsVersion, Instant consentedAt) {
    /** Giữ tập vai bất biến, không cho mã bên gọi sửa payload sau khi ánh xạ. */
    public CurrentAccountResponse {
        roles = Set.copyOf(roles);
    }

    /** Chỉ lấy các trường công khai từ view, không trả trực tiếp aggregate hoặc row CSDL. */
    public static CurrentAccountResponse fromDomain(CurrentAccountDetail view) {
        Objects.requireNonNull(view, "view must not be null.");
        return new CurrentAccountResponse(view.code(), view.phoneNumber(), view.roles(),
                view.branchCode(), view.termsVersion(), view.consentedAt());
    }

    /** Tránh in số điện thoại, vai, chi nhánh hoặc consent khi response vô tình được đưa vào log. */
    @Override
    public String toString() { return "CurrentAccountResponse[code=" + code + "]"; }
}
