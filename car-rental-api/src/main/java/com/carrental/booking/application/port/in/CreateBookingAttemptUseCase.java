package com.carrental.booking.application.port.in;

import com.carrental.booking.application.command.CreateBookingAttemptCommand;
import com.carrental.booking.application.view.BookingDetail;

/** Ranh giới savepoint nội bộ, chỉ được điều phối sau kiểm quyền/claim key ở CreateBookingUseCase. */
public interface CreateBookingAttemptUseCase {
    /** Một lần giữ xe và ghi đơn; trùng mã phải rollback savepoint gồm cả chỗ giữ trước khi trả lỗi. */
    BookingDetail createAttempt(CreateBookingAttemptCommand command);
}
