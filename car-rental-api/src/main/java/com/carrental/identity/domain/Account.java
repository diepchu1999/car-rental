package com.carrental.identity.domain;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.shared.validation.Validations;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Tài khoản nghiệp vụ bất biến theo BR-120/809, không chứa mật khẩu, OTP hoặc token.
 * Keycloak giữ vai; đối tượng này giữ định danh và bản sao đồng ý đầu tiên theo ADR-0006.
 * Mã TK-<6> do application cấp; ID số do CSDL sinh không nằm trong aggregate.
 * Tính duy nhất và an toàn khi hai request đầu tiên đồng thời phải được bảo vệ thêm ở CSDL.
 */
public final class Account {
    private static final Pattern CODE_PATTERN = Pattern.compile("TK-[A-Z0-9]{6}");

    private final String code;
    private final String keycloakSubject;
    private final VietnamMobileNumber phoneNumber;
    private final TermsConsent consent;

    /** Kiểm cấu trúc dùng chung cho tạo mới và khôi phục; consent được thiếu ở nhân viên chưa thuê. */
    private Account(String code, String keycloakSubject, VietnamMobileNumber phoneNumber, TermsConsent consent) {
        this.code = Validations.requiredText(code, "code");
        if (!CODE_PATTERN.matcher(this.code).matches()) {
            throw DomainException.invalidInput("code must contain TK- followed by six uppercase ASCII letters or digits.");
        }
        this.keycloakSubject = Validations.requiredText(keycloakSubject, "keycloakSubject");
        this.phoneNumber = Validations.required(phoneNumber, "phoneNumber");
        this.consent = consent;
    }

    /**
     * Dựng tài khoản từ danh tính đã xác thực ở lần gọi API đầu tiên, không tự ghi CSDL.
     * Khách bắt buộc có consent; nhân viên không bị ép đăng ký thuê xe theo BR-809.
     */
    public static Account create(String code, String keycloakSubject, VietnamMobileNumber phoneNumber,
                                 AccountRoles roles, TermsConsent consent) {
        requireCustomerConsent(roles, consent);
        return new Account(code, keycloakSubject, phoneNumber, consent);
    }

    /** Khôi phục dữ liệu đã lưu; vai không lưu ở aggregate và không suy ra từ việc có consent. */
    public static Account restore(String code, String keycloakSubject, VietnamMobileNumber phoneNumber,
                                  TermsConsent consent) {
        return new Account(code, keycloakSubject, phoneNumber, consent);
    }

    /**
     * Đối chiếu danh tính ở lượt gọi sau và bổ sung consent nếu nhân viên vừa đăng ký thuê.
     * Không đổi số hoặc subject (BR-124 ngoài phạm vi), không ghi đè bằng chứng đã có (BR-120).
     * Khách vẫn phải cung cấp bằng chứng hợp lệ dù tài khoản đã lưu consent.
     * Application phải lưu bổ sung nguyên tử để hai lượt gọi không ghi đè bằng chứng đầu tiên.
     */
    public Account withAuthenticatedIdentity(String subject, VietnamMobileNumber phone,
                                             AccountRoles roles, TermsConsent incomingConsent) {
        if (!keycloakSubject.equals(subject) || !phoneNumber.equals(phone)) {
            throw DomainException.forbidden(ErrorCode.ACCOUNT_IDENTITY_MISMATCH);
        }
        requireCustomerConsent(roles, incomingConsent);
        if (consent == null && incomingConsent != null) {
            return new Account(code, keycloakSubject, phoneNumber, incomingConsent);
        }
        return this;
    }

    /** BR-120: chỉ vai CUSTOMER đòi consent; vai nhân viên không tự sinh bằng chứng thay khách. */
    private static void requireCustomerConsent(AccountRoles roles, TermsConsent consent) {
        Validations.required(roles, "roles");
        if (roles.has(AccountRole.CUSTOMER) && consent == null) {
            throw DomainException.forbidden(ErrorCode.CONSENT_REQUIRED);
        }
    }

    /** Trả mã nghiệp vụ duy nhất, dùng thay ID số ở biên API. */
    public String code() { return code; }

    /** Trả subject dùng đối chiếu nội bộ với Keycloak; không coi subject là mã tài khoản công khai. */
    public String keycloakSubject() { return keycloakSubject; }

    /** Trả số đã chuẩn hoá; caller không được đưa giá trị này vào log. */
    public VietnamMobileNumber phoneNumber() { return phoneNumber; }

    /** Trả bằng chứng đầu tiên nếu có; không có không đồng nghĩa tài khoản không hợp lệ. */
    public Optional<TermsConsent> consent() { return Optional.ofNullable(consent); }

    /** Chỉ biểu diễn mã nghiệp vụ, không tự in số điện thoại hoặc định danh Keycloak. */
    @Override
    public String toString() { return "Account[code=" + code + "]"; }
}
