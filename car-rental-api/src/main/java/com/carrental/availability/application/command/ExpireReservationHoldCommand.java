package com.carrental.availability.application.command;

import com.carrental.shared.validation.Validations;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** BR-103: mốc dọn do job cung cấp, không phải TTL do khách chọn. */
public record ExpireReservationHoldCommand(String code, Instant expiredAt) {
    /** Chuẩn hoá mốc xuống micro giây để PostgreSQL không làm tròn lên khiến hold bị nhả sớm. */
    public ExpireReservationHoldCommand {
        code = Validations.requiredText(code, "code");
        expiredAt = Validations.required(expiredAt, "expiredAt").truncatedTo(ChronoUnit.MICROS);
    }
}
