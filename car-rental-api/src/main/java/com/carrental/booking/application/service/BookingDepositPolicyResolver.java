package com.carrental.booking.application.service;

import com.carrental.shared.rental.DriveMode;
import com.carrental.shared.rental.PickupMethod;
import com.carrental.shared.rental.RentalType;
import java.math.BigDecimal;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Phân giải tỷ lệ một lần tại biên tạo đơn; không rẽ nhánh RentalType ngoài resolver (R6, BR-201). */
@Component
class BookingDepositPolicyResolver {
    /**
     * BR-201: gói giờ/ngày cọc 30% tổng gồm bảo hiểm. Báo giá ngoài lát cắt là lỗi hợp đồng nội bộ:
     * pricing chưa thể cấp báo giá gói tháng/có tài xế/giao tận nơi, không đoán số tiền để nhận đơn.
     */
    BigDecimal resolve(RentalType type, DriveMode driveMode, PickupMethod pickupMethod) {
        Objects.requireNonNull(type, "rentalType");
        if (driveMode != DriveMode.SELF_DRIVE || pickupMethod != PickupMethod.BRANCH) {
            throw new IllegalStateException("The stored quote uses an unsupported booking mode.");
        }
        return switch (type) {
            case HOURLY, DAILY -> new BigDecimal("0.30");
            case MONTHLY -> throw new IllegalStateException("The stored quote uses an unsupported booking rental type.");
        };
    }
}
