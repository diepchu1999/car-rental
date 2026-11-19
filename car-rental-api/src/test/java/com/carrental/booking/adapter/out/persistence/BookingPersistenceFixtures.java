package com.carrental.booking.adapter.out.persistence;

import com.carrental.booking.domain.*;
import com.carrental.shared.rental.DriveMode;
import com.carrental.shared.rental.PickupMethod;
import com.carrental.shared.rental.RentalType;
import java.time.Instant;
import static com.carrental.booking.domain.BookingTestFixtures.*;

/** Fixture có mã riêng theo test, không cần sửa/xoá snapshot bất biến sau khi commit. */
final class BookingPersistenceFixtures {
    /** Tiện ích không có instance. */
    private BookingPersistenceFixtures() { }

    /** Tạo đơn hợp lệ với đúng quote và chủ của claim, giữ mốc micro giây. */
    static Booking booking(String suffix, String owner, Instant at) {
        return Booking.create("DT-2611-" + suffix, owner, "BG-2611-" + suffix, "XE-TEST01", "CN-TEST01",
                "KL-" + suffix, START, END, RentalType.DAILY, DriveMode.SELF_DRIVE, PickupMethod.BRANCH,
                price(), terms(), at.plusSeconds(2220), at);
    }

    /** Cửa sổ đúng 24 giờ, key cố ý nhận từ test để kiểm phạm vi và tranh chấp. */
    static BookingIdempotency claim(String suffix, String owner, String key, Instant at) {
        return BookingIdempotency.create(owner, key, "BG-2611-" + suffix, "DT-2611-" + suffix, at);
    }
}
