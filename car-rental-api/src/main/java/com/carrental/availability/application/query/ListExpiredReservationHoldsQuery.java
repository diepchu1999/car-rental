package com.carrental.availability.application.query;

import com.carrental.shared.validation.Validations;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** BR-103: dùng cùng một mốc dọn cho danh sách và từng dòng; không tự đọc lại Clock. */
public record ListExpiredReservationHoldsQuery(Instant expiredAt) {
    /** Cắt xuống micro giây giống lệnh nhả một hold, không làm tròn lên. */
    public ListExpiredReservationHoldsQuery {
        expiredAt = Validations.required(expiredAt, "expiredAt").truncatedTo(ChronoUnit.MICROS);
    }
}
