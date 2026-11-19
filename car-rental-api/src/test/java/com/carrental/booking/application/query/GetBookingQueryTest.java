package com.carrental.booking.application.query;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Biên GET chỉ kiểm mã có nội dung; quyền và tồn tại thuộc use case. */
class GetBookingQueryTest {
    /** Không ép mã lạ thành lỗi validation thay vì 404. */
    @Test
    void preservesLookupCode() {
        assertEquals("missing-booking", GetBookingQuery.from("missing-booking").code());
    }

    /** Mã null, rỗng hoặc trắng không được tới cổng đọc. */
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" ", "\t"})
    void rejectsMissingCode(String code) {
        assertEquals(ErrorCode.INVALID_REQUEST,
                assertThrows(DomainException.class, () -> GetBookingQuery.from(code)).errorCode());
    }
}
