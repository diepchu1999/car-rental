package com.carrental.booking.application.port.out;

import com.carrental.booking.domain.Booking;
import java.util.Optional;

/** Đọc snapshot trong schema booking; kiểm chủ ở use case và giới hạn thêm ngay tại SQL. */
public interface ReadBookingPort {
    /** Sai chủ có cùng kết quả rỗng như không tồn tại; không lọc hạn hoặc tính lại giá (BR-122). */
    Optional<Booking> loadOwned(String code, String customerAccountCode);
}
