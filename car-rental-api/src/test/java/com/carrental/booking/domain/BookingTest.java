package com.carrental.booking.domain;

import com.carrental.shared.rental.DriveMode;
import com.carrental.shared.rental.PickupMethod;
import com.carrental.shared.rental.RentalType;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static com.carrental.booking.domain.BookingTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Kiểm nền aggregate tạo đơn theo BR-102/103/122, không giả lập việc giữ chỗ thành công bằng domain. */
class BookingTest {
    /** Hạn giữ nguyên 37 phút từ availability; domain không tính lại một giờ hay đọc Clock. */
    @Test
    void startsPendingAndPreservesExactHoldExpiry() {
        var booking = booking();
        assertEquals(BookingStatus.PENDING_PAYMENT, booking.status());
        assertEquals(CREATED, booking.statusChangedAt());
        assertEquals(CREATED.plusSeconds(2_220), booking.holdExpiresAt());
        assertEquals(END, booking.endAt());
        assertEquals(7_200, booking.rentalTerms().turnaroundBuffer().toSeconds());
        assertEquals(330_000, booking.price().amounts().deposit());
    }

    /** Bản ghi cũ vẫn khôi phục được sau hạn, để bước hết hạn xử lý thay vì tự đổi trạng thái lúc đọc. */
    @Test
    void restoresExpiredBookingWithoutChangingStatusOrPrice() {
        var booking = booking();
        var restored = new Booking(booking.code(), booking.customerAccountCode(), booking.quoteCode(),
                booking.vehicleCode(), booking.branchCode(), booking.reservationCode(), START, END,
                booking.rentalType(), booking.driveMode(), booking.pickupMethod(), booking.price(), booking.rentalTerms(),
                booking.holdExpiresAt(), BookingStatus.PENDING_PAYMENT, CREATED, CREATED);
        assertEquals(booking, restored);
    }

    /** Tháng sai, mã số trần hoặc mã thiếu ký tự không trở thành đơn hợp lệ. */
    @ParameterizedTest
    @ValueSource(strings = {"", "123", "DT-2600-TEST01", "DT-2613-TEST01", "DT-2611-test01", "DT-2611-TEST0"})
    void rejectsInvalidBusinessCode(String code) {
        assertThrows(IllegalArgumentException.class, () -> create(code, CREATED.plusSeconds(60), CREATED));
    }

    /** Không chấp nhận hạn thiếu/hết ngay lúc tạo hoặc mốc không thể lưu nguyên vẹn. */
    @Test
    void rejectsInvalidExpiryAndTimestampPrecision() {
        assertThrows(IllegalArgumentException.class, () -> create("DT-2611-TEST01", CREATED, CREATED));
        assertThrows(IllegalArgumentException.class, () -> create("DT-2611-TEST01", null, CREATED));
        assertThrows(IllegalArgumentException.class, () -> create("DT-2611-TEST01", CREATED.plusSeconds(60), CREATED.plusNanos(1)));
    }

    /** Tạo cùng fixture nhưng thay đúng giá trị cần kiểm, không thay đổi production vì test. */
    private static Booking create(String code, Instant expiry, Instant created) {
        return Booking.create(code, "TK-OWNER1", "BG-2611-TEST01", "XE-TEST01", "CN-TEST01", "KL-TEST01",
                START, END, RentalType.DAILY, DriveMode.SELF_DRIVE, PickupMethod.BRANCH, price(), terms(), expiry, created);
    }
}
