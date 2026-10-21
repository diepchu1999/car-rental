package com.carrental.identity.application.view;

import java.time.Instant;
import java.util.Set;

/**
 * Thông tin của chính người gọi theo BR-120/803/809, không phải row CSDL hay response HTTP.
 * Vai là tập đã xác thực của request hiện tại; consent là bằng chứng đầu tiên lưu trong account.
 * Không chứa subject Keycloak, token hoặc ID số; branchCode null khi người gọi không có phạm vi chi nhánh.
 */
public record CurrentAccountDetail(String code, String phoneNumber, Set<String> roles,
                                   String branchCode, String termsVersion, Instant consentedAt) {
    /** Sao chép tập vai để view không đổi theo collection đầu vào và không cho caller sửa ngược. */
    public CurrentAccountDetail {
        roles = Set.copyOf(roles);
    }

    /** Không đưa số điện thoại, vai, chi nhánh hoặc bằng chứng đồng ý vào log khi in view. */
    @Override
    public String toString() { return "CurrentAccountDetail[code=" + code + "]"; }
}
