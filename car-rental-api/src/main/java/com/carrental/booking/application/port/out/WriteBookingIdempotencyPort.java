package com.carrental.booking.application.port.out;

import com.carrental.booking.application.view.BookingIdempotencyClaim;
import com.carrental.booking.domain.BookingIdempotency;

/** Chiếm và giữ khoá key theo khách trong cùng transaction tạo đơn (api-guideline §5). */
public interface WriteBookingIdempotencyPort {
    /**
     * Ghi nguyên tử trước khi đọc. Key hết hạn tại proposed.createdAt được thay; key còn hạn giữ nguyên.
     * Cùng key khác quote cũng trả bản cũ để use case báo IDEMPOTENCY_KEY_REUSED, không ghi đè nội dung.
     * Không commit claim thiếu đơn: FK hoãn được kiểm lúc commit transaction ngoài.
     */
    BookingIdempotencyClaim claim(BookingIdempotency proposed);

    /**
     * Chỉ sửa con trỏ của claim mới do chính transaction đang giữ, khi mã đơn ngẫu nhiên bị trùng.
     * Phải gọi trước khi ghi đơn thành công; giữ nguyên nội dung request và hạn. Sai expected phải lỗi.
     */
    void replaceClaimedBookingCode(BookingIdempotency expected, String replacementCode);
}
