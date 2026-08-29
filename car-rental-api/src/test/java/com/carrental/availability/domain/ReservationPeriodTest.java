package com.carrental.availability.domain;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kiểm hình dạng khoảng theo ADR-0005, khoảng vô hạn theo BR-015
 * và phép cộng đệm theo BR-109, BR-116 bằng domain thuần Java.
 *
 * <p>Mọi thời điểm đều cố định; không dùng Spring, Docker hoặc đồng hồ thật.
 * Các test này không thay thế test PostgreSQL cho ràng buộc chồng lịch
 * và tính liền kề của khoảng [).
 */
class ReservationPeriodTest {

    private static final Instant START = Instant.parse("2026-09-22T10:00:00Z");
    private static final Instant END = Instant.parse("2026-09-22T12:00:00Z");

    /**
     * Chứng minh constructor giữ nguyên hai đầu mút của khoảng hữu hạn.
     */
    @Test
    void constructsFinitePeriodWithoutChangingEndpoints() {
        ReservationPeriod period = new ReservationPeriod(START, END);

        assertEquals(START, period.startInclusive());
        assertEquals(END, period.endExclusive());
        assertFalse(period.isUnbounded());
    }

    /**
     * Chứng minh factory hữu hạn tạo đúng giá trị mà không tự cộng đệm.
     */
    @Test
    void finiteFactoryPreservesRequestedPeriod() {
        ReservationPeriod period = ReservationPeriod.finite(START, END);

        assertEquals(START, period.startInclusive());
        assertEquals(END, period.endExclusive());
        assertFalse(period.isUnbounded());
    }

    /**
     * Chứng minh constructor biểu diễn được cận trên bị thiếu bằng null.
     */
    @Test
    void constructsUnboundedPeriodWithoutSentinelDate() {
        ReservationPeriod period = new ReservationPeriod(START, null);

        assertEquals(START, period.startInclusive());
        assertNull(period.endExclusive());
        assertTrue(period.isUnbounded());
    }

    /**
     * Chứng minh factory khoảng vô hạn giữ đúng mốc bắt đầu theo BR-015.
     */
    @Test
    void unboundedFactoryPreservesStart() {
        ReservationPeriod period = ReservationPeriod.unboundedFrom(START);

        assertEquals(START, period.startInclusive());
        assertNull(period.endExclusive());
        assertTrue(period.isUnbounded());
    }

    /**
     * Chứng minh constructor luôn yêu cầu cận dưới, kể cả khi thiếu cả hai cận.
     *
     * @param endExclusive cận trên hữu hạn hoặc null
     */
    @ParameterizedTest
    @MethodSource("upperBounds")
    void constructorRejectsMissingStart(Instant endExclusive) {
        assertInvalidInput(
                () -> new ReservationPeriod(null, endExclusive),
                "startInclusive is required."
        );
    }

    /**
     * Chứng minh factory hữu hạn không cho thiếu cận dưới.
     */
    @Test
    void finiteFactoryRejectsMissingStart() {
        assertInvalidInput(
                () -> ReservationPeriod.finite(null, END),
                "startInclusive is required."
        );
    }

    /**
     * Chứng minh factory hữu hạn không âm thầm biến thiếu cận trên thành vô hạn.
     */
    @Test
    void finiteFactoryRejectsMissingEnd() {
        assertInvalidInput(
                () -> ReservationPeriod.finite(START, null),
                "endExclusive is required."
        );
    }

    /**
     * Chứng minh factory khoảng vô hạn vẫn bắt buộc có mốc bắt đầu.
     */
    @Test
    void unboundedFactoryRejectsMissingStart() {
        assertInvalidInput(
                () -> ReservationPeriod.unboundedFrom(null),
                "startInclusive is required."
        );
    }

    /**
     * Chứng minh không thể lách kiểm tra khoảng rỗng hoặc đảo ngược
     * bằng cách gọi constructor trực tiếp thay cho factory.
     *
     * @param endOffsetSeconds độ lệch của cận trên so với cận dưới
     */
    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsEmptyAndReversedPeriods(long endOffsetSeconds) {
        Instant invalidEnd = START.plusSeconds(endOffsetSeconds);

        assertInvalidInput(
                () -> new ReservationPeriod(START, invalidEnd),
                "endExclusive must be after startInclusive."
        );
        assertInvalidInput(
                () -> ReservationPeriod.finite(START, invalidEnd),
                "endExclusive must be after startInclusive."
        );
    }

    /**
     * Chứng minh có thể khôi phục khoảng lịch sử mà không kiểm lại so với hôm nay.
     */
    @Test
    void acceptsHistoricalPeriod() {
        Instant historicalStart = Instant.parse("2000-01-01T10:00:00Z");
        Instant historicalEnd = Instant.parse("2000-01-01T12:00:00Z");

        ReservationPeriod period = ReservationPeriod.finite(
                historicalStart,
                historicalEnd
        );

        assertEquals(historicalStart, period.startInclusive());
        assertEquals(historicalEnd, period.endExclusive());
        assertFalse(period.isUnbounded());
    }

    /**
     * Chứng minh đệm chỉ kéo dài cuối khoảng và không sửa khoảng thuê ban đầu.
     *
     * <p>Phủ đệm bằng không để kiểm biên, một giờ theo BR-116
     * và hai giờ theo BR-109; domain không tự phân giải loại gói thuê.
     *
     * @param bufferHours số giờ đệm được bên gọi cung cấp
     * @param expectedEndText cận trên mong đợi sau khi cộng đệm
     */
    @ParameterizedTest
    @CsvSource({
            "0, 2026-09-22T12:00:00Z",
            "1, 2026-09-22T13:00:00Z",
            "2, 2026-09-22T14:00:00Z"
    })
    void addsBufferOnlyToEndWithoutMutatingOriginal(
            long bufferHours,
            String expectedEndText
    ) {
        ReservationPeriod original = ReservationPeriod.finite(START, END);

        ReservationPeriod buffered = original.withBuffer(Duration.ofHours(bufferHours));

        assertNotSame(original, buffered);
        assertEquals(START, buffered.startInclusive());
        assertEquals(Instant.parse(expectedEndText), buffered.endExclusive());
        assertFalse(buffered.isUnbounded());
        assertEquals(START, original.startInclusive());
        assertEquals(END, original.endExclusive());
    }

    /**
     * Chứng minh thiếu đệm không bị tự diễn giải thành đệm bằng không.
     */
    @Test
    void rejectsMissingBuffer() {
        ReservationPeriod period = ReservationPeriod.finite(START, END);

        assertInvalidInput(
                () -> period.withBuffer(null),
                "buffer is required."
        );
    }

    /**
     * Chứng minh đệm âm bị từ chối, kể cả khi chỉ âm một nano giây.
     *
     * @param buffer độ dài đệm âm
     */
    @ParameterizedTest
    @MethodSource("negativeBuffers")
    void rejectsNegativeBuffer(Duration buffer) {
        ReservationPeriod period = ReservationPeriod.finite(START, END);

        assertInvalidInput(
                () -> period.withBuffer(buffer),
                "buffer must not be negative."
        );
    }

    /**
     * Chứng minh cộng đệm đòi khoảng hữu hạn, kể cả khi đệm bằng không.
     *
     * @param bufferHours số giờ đệm không âm
     */
    @ParameterizedTest
    @ValueSource(longs = {0, 1, 2})
    void rejectsBufferOnUnboundedPeriod(long bufferHours) {
        ReservationPeriod period = ReservationPeriod.unboundedFrom(START);

        assertInvalidInput(
                () -> period.withBuffer(Duration.ofHours(bufferHours)),
                "A finite period is required to apply a buffer."
        );
    }

    /**
     * Chứng minh lỗi vượt giới hạn Instant và lỗi tràn phép cộng số học
     * đều được chuyển thành lỗi đầu vào, không để lọt exception kỹ thuật.
     *
     * @param endExclusive cận trên hợp lệ trước khi cộng
     * @param buffer độ dài gây tràn khi cộng vào cận trên
     */
    @ParameterizedTest
    @MethodSource("overflowingBuffers")
    void rejectsTimeOverflow(Instant endExclusive, Duration buffer) {
        ReservationPeriod period = ReservationPeriod.finite(START, endExclusive);

        assertInvalidInput(
                () -> period.withBuffer(buffer),
                "Buffered period exceeds the supported time range."
        );
        assertEquals(START, period.startInclusive());
        assertEquals(endExclusive, period.endExclusive());
    }

    /**
     * Cung cấp cận trên hữu hạn và không chặn trên để kiểm thiếu cận dưới.
     *
     * @return hai dạng cận trên
     */
    private static Stream<Arguments> upperBounds() {
        return Stream.of(Arguments.of(END), Arguments.of((Object) null));
    }

    /**
     * Cung cấp đệm âm thông thường và đệm âm nhỏ hơn một giây.
     *
     * @return các độ dài âm cần từ chối
     */
    private static Stream<Duration> negativeBuffers() {
        return Stream.of(Duration.ofHours(-1), Duration.ofNanos(-1));
    }

    /**
     * Cung cấp phép cộng vượt miền Instant và phép cộng vượt miền long.
     *
     * @return các đầu vào gây hai dạng tràn thời gian
     */
    private static Stream<Arguments> overflowingBuffers() {
        return Stream.of(
                Arguments.of(Instant.MAX, Duration.ofNanos(1)),
                Arguments.of(END, Duration.ofSeconds(Long.MAX_VALUE))
        );
    }

    /**
     * Kiểm thao tác bị từ chối đúng loại exception, mã, nhóm và thông báo lỗi.
     *
     * @param action thao tác dự kiến bị từ chối
     * @param expectedMessage thông báo công khai mong đợi
     */
    private static void assertInvalidInput(Executable action, String expectedMessage) {
        DomainException failure = assertThrowsExactly(DomainException.class, action);

        assertEquals(ErrorCode.INVALID_REQUEST, failure.errorCode());
        assertEquals(DomainException.Category.INVALID_INPUT, failure.category());
        assertEquals(expectedMessage, failure.getMessage());
    }
}
