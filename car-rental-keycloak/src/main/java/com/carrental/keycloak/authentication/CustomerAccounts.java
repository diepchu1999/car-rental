package com.carrental.keycloak.authentication;

import java.time.Instant;
import java.util.Optional;

/** Ranh giới với kho người dùng Keycloak; chỉ gọi sau khi đã xác minh OTP của số điện thoại. */
interface CustomerAccounts {
    /** Tìm khách đã đồng ý mà không sửa bằng chứng cũ và không tạo người dùng. */
    Optional<String> findConsented(String realmId, String phone);

    /** Tạo khách hoặc bổ sung vai CUSTOMER cho nhân viên sau khi đã nhận sự đồng ý rõ ràng. */
    String accept(String realmId, String phone, String termsVersion, Instant consentedAt);
}
