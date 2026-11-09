package com.carrental.identity.domain;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import java.time.LocalDate;
import java.util.Objects;

/**
 * BR-106/106b/128/130: bằng chứng tối thiểu từ hai bản VERIFIED đang được head chọn.
 * Hạn null biểu diễn chưa có bản VERIFIED cho loại đó; không phải giấy tờ vô thời hạn.
 * Không giữ mã, số giấy tờ, ảnh, nhân thân hoặc bản nộp mới đang chờ duyệt.
 */
public record RentalDocuments(LocalDate identityCardExpiresOn, LocalDate driverLicenseExpiresOn,
        String licenseClass, Boolean automaticOnly, LocalDate driverLicenseIssuedOn) {
    /** Chặn metadata GPLX không đầy đủ; null không được ngầm hiểu là được lái số sàn. */
    public RentalDocuments {
        if (driverLicenseExpiresOn == null) {
            if (licenseClass != null || automaticOnly != null || driverLicenseIssuedOn != null) {
                throw new IllegalArgumentException("Driver license evidence is inconsistent.");
            }
        } else if (licenseClass == null || licenseClass.isBlank() || automaticOnly == null) {
            throw new IllegalArgumentException("Driver license evidence is incomplete.");
        }
    }

    /**
     * Kiểm theo thứ tự CCCD xác minh/hạn, GPLX xác minh/hạn, hạng, số chỗ/hộp số.
     * Theo Tech Owner chốt 31/10/2026, ngày hết hạn D còn hiệu lực hết D theo giờ Việt Nam.
     * tripEndDate là ngày trả thực tế đã đổi bằng múi giờ ứng dụng, không trừ nano hoặc cộng đệm.
     * Bản cũ thiếu ngày cấp trả lỗi chưa xác minh để khách nộp lại, không đoán chế độ theo tên hạng.
     * Bộ cấu hình do application cung cấp theo ADR-0013; chọn bảng bằng ngày cấp của bản đang dùng.
     */
    public void requireSelfDriveEligible(LocalDate tripEndDate, int seats, boolean manualTransmission,
            LicenseClassPolicy policy) {
        Objects.requireNonNull(tripEndDate, "tripEndDate");
        Objects.requireNonNull(policy, "policy");
        if (identityCardExpiresOn == null) throw DomainException.forbidden(ErrorCode.IDENTITY_CARD_NOT_VERIFIED);
        if (identityCardExpiresOn.isBefore(tripEndDate)) {
            throw DomainException.ruleViolation(ErrorCode.IDENTITY_CARD_EXPIRES_BEFORE_TRIP_END);
        }
        if (driverLicenseExpiresOn == null || driverLicenseIssuedOn == null) {
            throw DomainException.forbidden(ErrorCode.DRIVER_LICENSE_NOT_VERIFIED);
        }
        if (driverLicenseExpiresOn.isBefore(tripEndDate)) {
            throw DomainException.ruleViolation(ErrorCode.DRIVER_LICENSE_EXPIRES_BEFORE_TRIP_END);
        }
        var table = policy.tableFor(driverLicenseIssuedOn);
        if (!table.supportsClass(licenseClass)) {
            throw DomainException.ruleViolation(ErrorCode.DRIVER_LICENSE_CLASS_NOT_SUPPORTED);
        }
        if (!table.allowsSeats(licenseClass, seats) || (automaticOnly && manualTransmission)) {
            throw DomainException.ruleViolation(ErrorCode.DRIVER_LICENSE_NOT_SUITABLE_FOR_VEHICLE);
        }
    }

    /** Không để hạn/hạng/cờ hạn chế của giấy tờ lọt vào log mặc định. */
    @Override public String toString() { return "RentalDocuments[redacted]"; }
}
