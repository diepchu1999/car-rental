package com.carrental.booking.application.command;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Biên huỷ BR-301 không nhận chủ, mốc thời gian hoặc số tiền do khách chọn. */
class CancelBookingCommandTest {
    /** Mã có nội dung đi đến kiểm tồn tại/đúng chủ tại use case. */
    @Test
    void preservesLookupCode() {
        assertEquals("missing-booking", CancelBookingCommand.from("missing-booking").code());
    }

    /** Mã thiếu hoặc trắng trả INVALID_REQUEST trước khi ghi dữ liệu. */
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" ", "\t"})
    void rejectsMissingCode(String code) {
        assertEquals(ErrorCode.INVALID_REQUEST,
                assertThrows(DomainException.class, () -> CancelBookingCommand.from(code)).errorCode());
    }
}
