package com.carrental.booking.application.command;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Biên đầu vào không nhận giá/TTL; mã báo giá và key phải đầy đủ (BR-122, api-guideline §5). */
class CreateBookingCommandTest {
    /** Không chuẩn hoá key vì hai chuỗi khác nhau không được gộp thành một request. */
    @Test
    void preservesKeyExactly() {
        assertEquals(" key-01 ", CreateBookingCommand.from("BG-2611-TEST01", " key-01 ").idempotencyKey());
    }

    /** Chặn thiếu key trước khi bắt đầu giao dịch. */
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" ", "\t"})
    void rejectsMissingKey(String key) {
        assertEquals(ErrorCode.INVALID_REQUEST,
                assertThrows(DomainException.class, () -> CreateBookingCommand.from("BG-2611-TEST01", key)).errorCode());
    }

    /** Cả tháng sai, ID số và mã khác loại đều không phải tham chiếu báo giá. */
    @ParameterizedTest @NullAndEmptySource
    @ValueSource(strings = {" ", "123", "BG-2613-TEST01", "BG-2600-TEST01", "XE-TEST01", "BG-2611-test01"})
    void rejectsInvalidQuoteCode(String code) {
        assertEquals(ErrorCode.INVALID_REQUEST,
                assertThrows(DomainException.class, () -> CreateBookingCommand.from(code, "key")).errorCode());
    }
}
