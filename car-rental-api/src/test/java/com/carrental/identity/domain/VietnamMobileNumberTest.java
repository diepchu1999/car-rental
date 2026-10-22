package com.carrental.identity.domain;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** Kiểm cùng ví dụ chuẩn hoá với Keycloak, cộng chống rò rỉ số qua toString, BR-120/129. */
class VietnamMobileNumberTest {

    /** Cách viết khác nhau của cùng số phải có cùng giá trị, equals và hashCode. */
    @ParameterizedTest
    @ValueSource(strings = {"0912345678", "+84912345678", "0912 345 678", "(0912) 345-678"})
    void normalizesEquivalentNumbers(String input) {
        var actual = new VietnamMobileNumber(input);
        var expected = new VietnamMobileNumber("+84912345678");
        assertEquals("+84912345678", actual.value());
        assertEquals(expected, actual);
        assertEquals(expected.hashCode(), actual.hashCode());
    }

    /** Số bàn, quốc tế, extension, thiếu dữ liệu và ký tự điều khiển đều bị từ chối không lộ đầu vào. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "02838221234", "+14155552671", "12345", "0912345678 ext 1",
            "0912345678\nFORGED", "0912345678\r", "abcdef"})
    void rejectsNonVietnamMobileNumbers(String input) {
        var failure = assertThrowsExactly(DomainException.class, () -> new VietnamMobileNumber(input));
        assertEquals(ErrorCode.INVALID_REQUEST, failure.errorCode());
        assertEquals(DomainException.Category.INVALID_INPUT, failure.category());
        assertEquals("A valid Vietnamese mobile phone number is required.", failure.getMessage());
        assertNull(failure.getCause());
    }

    /** Giữ đúng giới hạn kỹ thuật 64 của provider, kể cả đầu vào chỉ thêm khoảng trắng. */
    @Test
    void rejectsInputBeyondTheProviderLengthGuard() {
        assertThrowsExactly(DomainException.class,
                () -> new VietnamMobileNumber("0912345678" + " ".repeat(55)));
    }

    /** Không rò rỉ số khi bị nối chuỗi hoặc truyền vào logger. */
    @Test
    void redactsImplicitStringRepresentation() {
        assertEquals("VietnamMobileNumber[redacted]", new VietnamMobileNumber("0912345678").toString());
    }
}
