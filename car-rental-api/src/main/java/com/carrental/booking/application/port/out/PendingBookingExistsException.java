package com.carrental.booking.application.port.out;

import java.io.Serial;

/** Vi phạm đúng unique đơn chờ của khách (BR-131); application sẽ chuyển thành mã lỗi nghiệp vụ. */
public final class PendingBookingExistsException extends RuntimeException {
    @Serial private static final long serialVersionUID = 1L;

    /** Giữ nguyên nguyên nhân để chẩn đoán; không đưa thông báo PostgreSQL vào response. */
    public PendingBookingExistsException(Throwable cause) {
        super("The customer already has a pending booking.", cause);
    }
}
