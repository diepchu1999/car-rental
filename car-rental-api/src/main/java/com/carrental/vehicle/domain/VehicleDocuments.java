package com.carrental.vehicle.domain;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Biểu diễn các mốc hết hạn giấy tờ bắt buộc của xe theo BR-005.
 *
 * <p>Task hiện tại lưu ngày hết hạn của đăng kiểm và bảo hiểm TNDS
 * bắt buộc, chưa quản lý file giấy tờ hoặc nơi lưu trữ chúng.
 *
 * <p>Cho phép ngày hết hạn bị thiếu hoặc đã qua khi biểu diễn hồ sơ xe.
 * Điều kiện có đủ giấy tờ và còn hạn được kiểm tại thời điểm duyệt,
 * không kiểm ngay khi khởi tạo đối tượng.
 *
 * <p>Theo quy ước ngày hết hạn của BR-015, giấy tờ hết hạn ngày D
 * không còn hợp lệ từ ngày D. Đối tượng này không tạo khóa lịch;
 * cơ chế đó thuộc task có module availability.
 *
 * @param inspectionExpiresOn ngày đăng kiểm bắt đầu hết hiệu lực,
 *                            có thể null nếu chưa được cung cấp
 * @param liabilityInsuranceExpiresOn ngày bảo hiểm TNDS bắt buộc
 *                                    bắt đầu hết hiệu lực,
 *                                    có thể null nếu chưa được cung cấp
 */
public record VehicleDocuments(
        LocalDate inspectionExpiresOn,
        LocalDate liabilityInsuranceExpiresOn
) {

    /**
     * Kiểm tra giấy tờ có đủ và còn hạn tại ngày duyệt theo BR-005.
     *
     * <p>Cả hai ngày hết hạn phải sau ngày duyệt.
     * Ngày hết hạn bằng ngày duyệt cũng bị xem là đã hết hạn.
     *
     * <p>Kiểm thiếu giấy tờ trước khi kiểm hạn.
     * Nếu thiếu một trong hai ngày, báo VEHICLE_DOCUMENT_MISSING.
     * Nếu có đủ nhưng một giấy tờ hết hạn, báo VEHICLE_DOCUMENT_EXPIRED.
     *
     * <p>Ngày duyệt do tầng application cung cấp.
     * Domain không tự đọc đồng hồ hệ thống, giúp hành vi xác định
     * và kiểm thử được bằng ngày cố định.
     *
     * @param approvalDate ngày thực hiện duyệt xe
     * @throws NullPointerException nếu tầng gọi không cung cấp ngày duyệt
     * @throws DomainException nếu thiếu giấy tờ hoặc giấy tờ đã hết hạn
     */
    public void validateForApproval(LocalDate approvalDate) {
        Objects.requireNonNull(
                approvalDate,
                "approvalDate must not be null."
        );

        if (inspectionExpiresOn == null
                || liabilityInsuranceExpiresOn == null) {
            throw DomainException.ruleViolation(
                    ErrorCode.VEHICLE_DOCUMENT_MISSING
            );
        }

        if (!inspectionExpiresOn.isAfter(approvalDate)
                || !liabilityInsuranceExpiresOn.isAfter(approvalDate)) {
            throw DomainException.ruleViolation(
                    ErrorCode.VEHICLE_DOCUMENT_EXPIRED
            );
        }
    }
}