package com.carrental.booking.application.service;

import com.carrental.shared.rental.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

/** Tỷ lệ gói giờ/ngày theo BR-201; ngoài lát cắt không tự đoán chính sách. */
class BookingDepositPolicyResolverTest {
    private final BookingDepositPolicyResolver resolver = new BookingDepositPolicyResolver();

    /** Cả hai gói hiện có dùng 30%, được áp lên tổng gồm bảo hiểm ở use case. */
    @ParameterizedTest @EnumSource(value = RentalType.class, names = {"HOURLY", "DAILY"})
    void resolvesSupportedPackages(RentalType type) {
        assertEquals(new BigDecimal("0.30"), resolver.resolve(type, DriveMode.SELF_DRIVE, PickupMethod.BRANCH));
    }

    /** Không nhận báo giá gói tháng hoặc phương thức chưa được pricing phát hành. */
    @Test
    void rejectsUnsupportedInternalQuoteModes() {
        assertThrows(IllegalStateException.class, () -> resolver.resolve(RentalType.MONTHLY, DriveMode.SELF_DRIVE, PickupMethod.BRANCH));
        assertThrows(IllegalStateException.class, () -> resolver.resolve(RentalType.DAILY, DriveMode.WITH_DRIVER, PickupMethod.BRANCH));
        assertThrows(IllegalStateException.class, () -> resolver.resolve(RentalType.DAILY, DriveMode.SELF_DRIVE, PickupMethod.DELIVERY));
    }
}
