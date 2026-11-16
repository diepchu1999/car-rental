package com.carrental.booking.application.port.in;

import com.carrental.booking.application.command.CancelBookingCommand;
import com.carrental.booking.application.view.BookingDetail;

/** BR-301/304: khách huỷ đơn chờ của mình và nhả chỗ trong cùng transaction. */
public interface CancelBookingUseCase {
    /** Không thu/hoàn tiền; huỷ lặp hoặc thua cuộc đua huỷ trả BOOKING_INVALID_STATUS_TRANSITION. */
    BookingDetail cancel(CancelBookingCommand command);
}
