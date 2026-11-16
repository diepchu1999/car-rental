package com.carrental.availability.application.port.in;

import com.carrental.availability.api.HoldExpiryResult;
import com.carrental.availability.application.command.ExpireReservationHoldCommand;

/** BR-103/304: xử lý một hold, tham gia giao dịch của bên điều phối đơn. */
public interface ExpireReservationHoldUseCase {
    /** Không nhả CONFIRMED hoặc hold chưa đến hạn; thiếu mã trả RESERVATION_NOT_FOUND. */
    HoldExpiryResult expire(ExpireReservationHoldCommand command);
}
