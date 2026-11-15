package com.carrental.booking.domain;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static com.carrental.booking.domain.BookingTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Quy tắc huỷ đơn chờ và hết hạn theo BR-103/301; việc nhả chỗ nguyên tử thuộc application (BR-304). */
class BookingCancellationTest {
    /** Khách huỷ được trước, đúng hoặc sau hạn khi đơn vẫn đang chờ; không đổi snapshot (ADR-0014). */
    @ParameterizedTest
    @ValueSource(longs = {-1, 0, 1})
    void customerCancelsPendingBeforeAtOrAfterDeadline(long secondsFromDeadline) {
        var original = booking();
        var at = original.holdExpiresAt().plusSeconds(secondsFromDeadline);
        assertCancelledCopy(original, original.cancel(at), at);
    }

    /** Phá hoại: huỷ lại bị từ chối bằng mã đã quy định, thuộc nhóm sẽ ánh xạ HTTP 422. */
    @Test
    void rejectsRepeatedCustomerCancellation() {
        var cancelled = booking().cancel(CREATED.plusSeconds(10));
        var error = assertThrows(DomainException.class, () -> cancelled.cancel(CREATED.plusSeconds(11)));
        assertEquals(ErrorCode.BOOKING_INVALID_STATUS_TRANSITION, error.errorCode());
        assertEquals(DomainException.Category.RULE_VIOLATION, error.category());
        assertEquals(CREATED.plusSeconds(10), cancelled.statusChangedAt());
    }

    /** Còn một micro giây chưa đến hạn thì job không tạo chuyển trạng thái. */
    @Test
    void doesNotExpireOneMicrosecondBeforeDeadline() {
        var original = booking();
        assertTrue(original.expire(original.holdExpiresAt().minusNanos(1_000)).isEmpty());
        assertEquals(BookingStatus.PENDING_PAYMENT, original.status());
    }

    /** Hạn 37 phút của fixture phải được dùng nguyên vẹn, không chờ đến một giờ (BR-103). */
    @ParameterizedTest
    @ValueSource(longs = {0, 1, 3_600})
    void expiresAtOrAfterStoredDeadline(long secondsFromDeadline) {
        var original = booking();
        var at = original.holdExpiresAt().plusSeconds(secondsFromDeadline);
        assertCancelledCopy(original, original.expire(at).orElseThrow(), at);
    }

    /** Job chạy lại sau khi khách huỷ không đổi mốc huỷ hoặc tạo thêm chuyển trạng thái. */
    @Test
    void ignoresBookingAlreadyCancelledByCustomer() {
        var cancelled = booking().cancel(CREATED.plusSeconds(10));
        assertTrue(cancelled.expire(cancelled.holdExpiresAt()).isEmpty());
        assertEquals(CREATED.plusSeconds(10), cancelled.statusChangedAt());
    }

    /** Job hết hạn lặp lại không ghi đè mốc của lượt đầu. */
    @Test
    void ignoresBookingAlreadyExpired() {
        var original = booking();
        var cancelled = original.expire(original.holdExpiresAt()).orElseThrow();
        assertTrue(cancelled.expire(original.holdExpiresAt().plusSeconds(60)).isEmpty());
        assertEquals(original.holdExpiresAt(), cancelled.statusChangedAt());
    }

    /** Khách huỷ sau khi job xử lý phải nhận cùng lỗi sai trạng thái, không coi là huỷ thành công mới. */
    @Test
    void rejectsCustomerCancellationAfterExpiryWasApplied() {
        var original = booking();
        var cancelled = original.expire(original.holdExpiresAt()).orElseThrow();
        var error = assertThrows(DomainException.class, () -> cancelled.cancel(original.holdExpiresAt()));
        assertEquals(ErrorCode.BOOKING_INVALID_STATUS_TRANSITION, error.errorCode());
        assertEquals(DomainException.Category.RULE_VIOLATION, error.category());
    }

    /** Phá hoại: mốc thiếu, mất độ chính xác hoặc lùi trước mốc tạo không được dùng để đổi trạng thái. */
    @Test
    void rejectsInvalidTransitionTimestamps() {
        var original = booking();
        for (Instant at : new Instant[] {null, CREATED.plusNanos(1), CREATED.minusNanos(1_000)}) {
            assertThrows(IllegalArgumentException.class, () -> original.cancel(at));
            assertThrows(IllegalArgumentException.class, () -> original.expire(at));
        }
    }

    /** Kiểm mốc gần nhất chứ không chỉ mốc tạo, kể cả lần job không còn gì để xử lý. */
    @Test
    void rejectsTimestampBeforeLatestStatusChange() {
        var cancelled = booking().cancel(CREATED.plusSeconds(10));
        assertThrows(IllegalArgumentException.class, () -> cancelled.cancel(CREATED.plusSeconds(9)));
        assertThrows(IllegalArgumentException.class, () -> cancelled.expire(CREATED.plusSeconds(9)));
    }

    /** So sánh toàn bộ aggregate để bắt mọi thay đổi nhầm tham chiếu, giá, điều kiện hoặc hạn. */
    private static void assertCancelledCopy(Booking original, Booking cancelled, Instant at) {
        assertEquals(new Booking(original.code(), original.customerAccountCode(), original.quoteCode(),
                original.vehicleCode(), original.branchCode(), original.reservationCode(), original.startAt(),
                original.endAt(), original.rentalType(), original.driveMode(), original.pickupMethod(),
                original.price(), original.rentalTerms(), original.holdExpiresAt(), BookingStatus.CANCELLED,
                original.createdAt(), at), cancelled);
        assertEquals(BookingStatus.PENDING_PAYMENT, original.status());
        assertEquals(CREATED, original.statusChangedAt());
    }
}
