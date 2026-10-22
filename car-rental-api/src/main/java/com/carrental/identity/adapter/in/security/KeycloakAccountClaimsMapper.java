package com.carrental.identity.adapter.in.security;

import com.carrental.identity.application.command.ResolveAccountCommand;
import com.carrental.identity.domain.AccountRole;
import com.carrental.identity.domain.AccountRoles;
import com.carrental.identity.domain.TermsConsent;
import com.carrental.identity.domain.VietnamMobileNumber;
import com.carrental.shared.error.DomainException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Chuyển claims Keycloak đã xác minh thành đầu vào thuần của use case theo BR-120/129/801/809.
 * Chỉ gọi sau JwtDecoder; lớp này không xác minh chữ ký, không đọc token thô, không ghi CSDL.
 * Không tự ép số/object thành chuỗi, không lấy vai từ resource_access, scope hoặc header HTTP.
 */
@Component
class KeycloakAccountClaimsMapper {
    private static final Map<String, AccountRole> PROJECT_ROLES = Arrays.stream(AccountRole.values())
            .collect(Collectors.toUnmodifiableMap(Enum::name, Function.identity()));

    /** Dựng mapper không giữ trạng thái người dùng giữa các request. */
    KeycloakAccountClaimsMapper() { }

    /**
     * Giữ nguyên subject và bằng chứng consent; chỉ số điện thoại được chuẩn hoá theo domain.
     * Claim sai cấu trúc trả UNAUTHENTICATED; thiếu bằng chứng vẫn giữ CONSENT_REQUIRED của domain.
     * Không kèm cause của parser vì thông báo gốc có thể chứa số điện thoại hoặc nội dung claim.
     */
    ResolveAccountCommand map(Jwt verifiedJwt) {
        if (verifiedJwt == null) throw DomainException.unauthenticated();
        Map<String, Object> claims = verifiedJwt.getClaims();
        String subject = text(claims.get("sub"));
        if (subject == null || subject.isBlank() || !subject.equals(subject.strip())) {
            throw DomainException.unauthenticated();
        }
        VietnamMobileNumber phone = phone(claims.get("phone_number"));
        AccountRoles roles = roles(claims.get("realm_access"));
        String version = text(claims.get("terms_version"));
        String timestamp = text(claims.get("consented_at"));
        Instant consentedAt = timestamp == null ? null : consentedAt(timestamp);
        TermsConsent consent = version == null && timestamp == null ? null : new TermsConsent(version, consentedAt);
        return new ResolveAccountCommand(subject, phone, roles, consent);
    }

    /** Chỉ nhận chuỗi JSON hoặc giá trị thiếu; không phản chiếu ký tự điều khiển vào tầng trong. */
    private String text(Object value) {
        if (value == null) return null;
        if (!(value instanceof String text) || text.chars().anyMatch(Character::isISOControl)) {
            throw DomainException.unauthenticated();
        }
        return text;
    }

    /** Số không hợp lệ trong token là lỗi danh tính, không phải lỗi body 400 của API nghiệp vụ. */
    private VietnamMobileNumber phone(Object value) {
        String number = text(value);
        try { return new VietnamMobileNumber(number); }
        catch (DomainException invalid) { throw DomainException.unauthenticated(); }
    }

    /**
     * Chỉ lấy đúng tên realm role BR-801; vai hệ thống/ngoài dự án không cấp thêm quyền.
     * Thiếu tập vai tạo tập rỗng, không tự cấp CUSTOMER; sai cấu trúc hoặc nhiều vai nhân viên bị chặn.
     */
    private AccountRoles roles(Object value) {
        var result = EnumSet.noneOf(AccountRole.class);
        if (value == null) return new AccountRoles(result);
        if (!(value instanceof Map<?, ?> access)) throw DomainException.unauthenticated();
        Object rawRoles = access.get("roles");
        if (rawRoles == null) return new AccountRoles(result);
        if (!(rawRoles instanceof List<?> names)) throw DomainException.unauthenticated();
        for (Object item : names) {
            String name = text(item);
            if (name == null || name.isBlank()) throw DomainException.unauthenticated();
            AccountRole role = PROJECT_ROLES.get(name);
            if (role != null) result.add(role);
        }
        try { return new AccountRoles(result); }
        catch (IllegalArgumentException invalid) { throw DomainException.unauthenticated(); }
    }

    /** Đọc đúng ISO instant do SPI lưu, không thay bằng giờ hiện tại hoặc đoán epoch giây/mili giây. */
    private Instant consentedAt(String timestamp) {
        try { return Instant.parse(timestamp); }
        catch (DateTimeParseException invalid) { throw DomainException.unauthenticated(); }
    }
}
