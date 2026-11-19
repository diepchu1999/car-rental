package com.carrental.booking.application.view;

import com.carrental.booking.domain.BookingIdempotency;
import java.util.Objects;

/**
 * Kết quả chiếm key trong transaction, chưa phải kết quả HTTP đã commit.
 * @param newlyClaimed true khi request này tạo/thay key hết hạn; false khi phải phát lại/đối chiếu nội dung
 * @param record dữ liệu đang bị khoá tới hết transaction, không phải dữ liệu đề nghị của bên thua
 */
public record BookingIdempotencyClaim(boolean newlyClaimed, BookingIdempotency record) {
    /** Không cho trả claim thiếu dữ liệu đối chiếu. */
    public BookingIdempotencyClaim { Objects.requireNonNull(record, "record"); }
}
