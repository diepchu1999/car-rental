package com.carrental.booking.application.port.in;

import com.carrental.booking.application.command.ExpireBookingHoldCommand;

/** BR-103/304: một transaction riêng cho mỗi dòng, gồm đơn, lịch sử và nhả chỗ. */
public interface ExpireBookingHoldUseCase {
    /** True khi vừa huỷ đơn hoặc nhả hold độc lập; lỗi phải rollback toàn bộ dòng đó. */
    boolean expire(ExpireBookingHoldCommand command);
}
