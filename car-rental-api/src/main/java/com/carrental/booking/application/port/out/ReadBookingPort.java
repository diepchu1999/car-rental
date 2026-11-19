package com.carrental.booking.application.port.out;

import com.carrental.booking.application.view.ExpiredBookingHold;
import java.time.Instant;
import com.carrental.booking.domain.Booking;
import com.carrental.booking.application.query.BookingPagePosition;
import com.carrental.booking.application.view.BookingListItem;
import java.util.List;
import java.util.Optional;

/** Đọc snapshot trong schema booking; kiểm chủ ở use case và giới hạn thêm ngay tại SQL. */
public interface ReadBookingPort {
    /** Chỉ cho job nội bộ BR-103, không dùng thay truy vấn đúng chủ của HTTP. */
    Optional<Booking> loadForExpiry(String code);

    /** Đơn chờ đến hạn, kể cả hold đã được job cũ nhả; chỉ đọc schema booking. */
    List<ExpiredBookingHold> findExpiredPending(Instant expiredAt);

    /** Sai chủ có cùng kết quả rỗng như không tồn tại; không lọc hạn hoặc tính lại giá (BR-122). */
    Optional<Booking> loadOwned(String code, String customerAccountCode);

    /** Đọc tối đa fetchLimit hàng đúng chủ, sau khóa giảm dần; null là trang đầu, không lọc trạng thái. */
    List<BookingListItem> searchOwned(String customerAccountCode, BookingPagePosition after, int fetchLimit);
}
