package com.carrental.keycloak.otp;

import com.carrental.keycloak.phone.VietnamMobileNumber;

/** Ràng buộc mã vào realm, phiên xác thực (gồm client/tab), số điện thoại và mục đích. */
public record OtpScope(String realmId, String authenticationSessionId, String phoneNumber, Purpose purpose) {
    /** Không cho dùng scope rỗng hoặc đưa dữ liệu không chuẩn vào kho mã. */
    public OtpScope {
        if (realmId == null || realmId.isBlank() || authenticationSessionId == null
                || authenticationSessionId.isBlank() || purpose == null) {
            throw new IllegalArgumentException("Realm, authentication session and OTP purpose are required.");
        }
        phoneNumber = VietnamMobileNumber.normalize(phoneNumber);
    }

    /** Hai đường đăng nhập được tách biệt dù dùng cùng số điện thoại. */
    public enum Purpose { CUSTOMER_LOGIN, EMPLOYEE_LOGIN }

    /** Không vô tình in số điện thoại khi log scope. */
    @Override public String toString() { return "OtpScope[redacted]"; }
}
