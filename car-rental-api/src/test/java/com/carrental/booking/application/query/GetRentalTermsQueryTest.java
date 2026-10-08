package com.carrental.booking.application.query;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.shared.rental.PickupMethod;
import com.carrental.shared.rental.RentalType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

/** Kiểm hợp đồng đầu vào BR-125 của query, độc lập với Spring. */
class GetRentalTermsQueryTest {
    private static final Instant START = Instant.parse("2026-10-05T03:00:00Z");
    private static final Instant END = START.plusSeconds(14_400);

    /** Factory và constructor giữ nguyên mọi trường, không cộng đệm hoặc chuẩn hóa thời điểm. */
    @Test
    void preservesRawValues() {
        GetRentalTermsQuery query = GetRentalTermsQuery.from(RentalType.HOURLY, PickupMethod.BRANCH, START, END);
        assertEquals(new GetRentalTermsQuery(RentalType.HOURLY, PickupMethod.BRANCH, START, END), query);
        assertEquals(START, query.startInclusive());
        assertEquals(END, query.endExclusive());
    }

    /** Mỗi trường thiếu đều phải có lỗi riêng ở cả hai đường khởi tạo. */
    @ParameterizedTest
    @ValueSource(strings = {"rentalType", "pickupMethod", "startInclusive", "endExclusive"})
    void rejectsEveryMissingField(String field) {
        RentalType rentalType = field.equals("rentalType") ? null : RentalType.HOURLY;
        PickupMethod pickupMethod = field.equals("pickupMethod") ? null : PickupMethod.BRANCH;
        Instant start = field.equals("startInclusive") ? null : START;
        Instant end = field.equals("endExclusive") ? null : END;
        assertInvalid(() -> new GetRentalTermsQuery(rentalType, pickupMethod, start, end), field + " is required.");
        assertInvalid(() -> GetRentalTermsQuery.from(rentalType, pickupMethod, start, end), field + " is required.");
    }

    /** Bắt cả khoảng rỗng và đảo chiều, giữ đúng mã INVALID_REQUEST. */
    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void rejectsInvalidPeriod(long seconds) {
        assertInvalid(() -> GetRentalTermsQuery.from(RentalType.DAILY, PickupMethod.BRANCH,
                START, START.plusSeconds(seconds)), "endExclusive must be after startInclusive.");
    }

    /** Kiểm mã, nhóm và thông báo để test không xanh do lỗi khác. */
    private static void assertInvalid(org.junit.jupiter.api.function.Executable action, String message) {
        DomainException failure = assertThrowsExactly(DomainException.class, action);
        assertEquals(ErrorCode.INVALID_REQUEST, failure.errorCode());
        assertEquals(DomainException.Category.INVALID_INPUT, failure.category());
        assertEquals(message, failure.getMessage());
    }
}
