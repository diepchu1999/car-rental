package com.carrental.booking.application.query;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Giữ quy ước phân trang của api-guideline §8 ngay tại đầu vào application. */
class SearchBookingsQueryTest {
    /** Chỉ thiếu limit mới dùng mặc định, không tự cắt giá trị quá lớn. */
    @Test
    void acceptsDefaultAndBoundaryLimits() {
        assertEquals(20, SearchBookingsQuery.from(null, null).limit());
        assertEquals(1, SearchBookingsQuery.from(1, null).limit());
        assertEquals(100, SearchBookingsQuery.from(100, null).limit());
    }

    /** Đầu vào vượt giới hạn phải báo INVALID_REQUEST. */
    @ParameterizedTest @ValueSource(ints = {-1, 0, 101, Integer.MAX_VALUE})
    void rejectsInvalidLimit(int limit) {
        var failure = assertThrows(DomainException.class, () -> SearchBookingsQuery.from(limit, null));
        assertEquals(ErrorCode.INVALID_REQUEST, failure.errorCode());
    }

    /** Cursor trống không bị hiểu nhầm thành yêu cầu tải trang đầu. */
    @Test
    void rejectsBlankAndOversizedCursor() {
        for (var cursor : new String[]{"", " ", "a".repeat(257)}) {
            assertThrows(DomainException.class, () -> SearchBookingsQuery.from(20, cursor));
        }
        assertNull(SearchBookingsQuery.from(20, null).cursor());
    }
}
