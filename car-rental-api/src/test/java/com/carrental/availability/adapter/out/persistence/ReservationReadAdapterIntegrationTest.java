package com.carrental.availability.adapter.out.persistence;

import com.carrental.PostgresTestConfiguration;
import com.carrental.availability.application.port.out.ReadReservationPort;
import com.carrental.availability.application.port.out.WriteReservationPort;
import com.carrental.availability.domain.Reservation;
import com.carrental.availability.domain.ReservationKind;
import com.carrental.availability.domain.ReservationPeriod;
import com.carrental.availability.domain.ReservationStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Kiểm đọc aggregate qua PostgreSQL thật theo BR-103, BR-015 và BR-427.
 *
 * <p>Chèn qua port ghi, đọc qua port đọc thật; không tạo view trung gian.
 * Mốc năm 2000 kiểm việc đọc dữ liệu cũ mà không dùng lại Clock hay TTL hiện tại.
 * Mỗi test rollback dữ liệu trong database riêng của Testcontainers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
@Transactional
class ReservationReadAdapterIntegrationTest {

    private static final String CODE = "KL-READ01";
    private static final long VEHICLE_ID = 9_000_000_000_401L;
    private static final Instant CREATED = OffsetDateTime.parse("2000-01-01T08:02:03.123456+07:00").toInstant();
    private static final Instant START = OffsetDateTime.parse("2030-10-01T10:00:00+07:00").toInstant();
    private static final Instant END = START.plusSeconds(14400);

    @Autowired
    private WriteReservationPort writes;
    @Autowired
    private ReadReservationPort reads;

    /**
     * Kiểm mọi trạng thái thuê được khôi phục, gồm HELD đã quá hạn và hai trạng thái cuối.
     *
     * @param status trạng thái RENTAL hợp lệ cần đọc lại
     */
    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = "BLOCKED", mode = EnumSource.Mode.EXCLUDE)
    void restoresRentalWithoutChangingStatusExpiryOrBuffer(ReservationStatus status) {
        Reservation expected = Reservation.restore(CODE, VEHICLE_ID, ReservationPeriod.finite(START, END),
                ReservationKind.RENTAL, status, "read-booking", null,
                CREATED.plus(Duration.ofMinutes(90)), CREATED, CREATED.plusSeconds(17));
        writes.insert(expected).orElseThrow();
        assertSameFields(expected, reads.loadAggregate(CODE).orElseThrow());
    }

    /**
     * Kiểm cả năm nguyên nhân khóa vận hành giữ nguyên lý do, khoảng và các giá trị null.
     *
     * @param kind nguyên nhân khóa vận hành
     */
    @ParameterizedTest
    @EnumSource(value = ReservationKind.class, names = "RENTAL", mode = EnumSource.Mode.EXCLUDE)
    void restoresOperationalReservation(ReservationKind kind) {
        ReservationPeriod period = kind == ReservationKind.COMPLIANCE_HOLD
                ? ReservationPeriod.unboundedFrom(START) : ReservationPeriod.finite(START, END);
        Reservation expected = Reservation.createBlocked(CODE, VEHICLE_ID,
                period, kind, "  Scheduled work  ", CREATED);
        writes.insert(expected).orElseThrow();
        assertSameFields(expected, reads.loadAggregate(CODE).orElseThrow());
    }

    /** Kiểm compliance không chặn trên được đọc thành end=null, không dùng ngày giả. */
    @Test
    void restoresUnboundedCompliance() {
        Reservation expected = Reservation.createBlocked(CODE, VEHICLE_ID,
                ReservationPeriod.unboundedFrom(START), ReservationKind.COMPLIANCE_HOLD, null, CREATED);
        writes.insert(expected).orElseThrow();
        Reservation actual = reads.loadAggregate(CODE).orElseThrow();
        assertSameFields(expected, actual);
        assertTrue(actual.period().isUnbounded());
    }

    /** Kiểm khóa vận hành COMPLETED vẫn được đọc nguyên trạng như hồ sơ đã lưu. */
    @Test
    void restoresCompletedOperationalReservation() {
        Reservation expected = Reservation.createBlocked(CODE, VEHICLE_ID,
                ReservationPeriod.finite(START, END), ReservationKind.MAINTENANCE, null, CREATED)
                .complete(CREATED.plusSeconds(600));
        writes.insert(expected).orElseThrow();
        assertSameFields(expected, reads.loadAggregate(CODE).orElseThrow());
    }

    /** Kiểm cột TTL null ngoài HELD được giữ nguyên, không tự tính từ cấu hình. */
    @Test
    void preservesNullableExpiryOutsideHeld() {
        Reservation expected = Reservation.restore(CODE, VEHICLE_ID, ReservationPeriod.finite(START, END),
                ReservationKind.RENTAL, ReservationStatus.CONFIRMED, "read-booking", null,
                null, CREATED, CREATED.plusSeconds(17));
        writes.insert(expected).orElseThrow();
        assertSameFields(expected, reads.loadAggregate(CODE).orElseThrow());
    }

    /** Kiểm không có mã thì trả rỗng, không chọn đại một khóa khác. */
    @Test
    void returnsEmptyForUnknownCode() {
        writes.insert(Reservation.createHeld(CODE, VEHICLE_ID, ReservationPeriod.finite(START, END),
                "read-booking", Duration.ofHours(1), CREATED)).orElseThrow();
        assertTrue(reads.loadAggregate("KL-NONE01").isEmpty());
    }

    /** Kiểm hai khóa cùng bookingCode vẫn được chọn chính xác bằng từng mã reservation. */
    @Test
    void selectsByReservationCodeNotBookingCode() {
        Reservation first = Reservation.createHeld(CODE, VEHICLE_ID, ReservationPeriod.finite(START, END),
                "shared-booking", Duration.ofHours(1), CREATED);
        Reservation second = Reservation.createHeld("KL-READ02", VEHICLE_ID,
                ReservationPeriod.finite(END, END.plusSeconds(3600)),
                "shared-booking", Duration.ofMinutes(90), CREATED.plusSeconds(10));
        writes.insert(first).orElseThrow();
        writes.insert(second).orElseThrow();
        assertSameFields(first, reads.loadAggregate(first.code()).orElseThrow());
        assertSameFields(second, reads.loadAggregate(second.code()).orElseThrow());
        assertTrue(reads.loadAggregate("shared-booking").isEmpty());
    }

    /** Đối chiếu toàn bộ thuộc tính domain để phát hiện tráo cột, đổi trạng thái hoặc tính lại thời gian. */
    private static void assertSameFields(Reservation expected, Reservation actual) {
        assertEquals(expected.code(), actual.code());
        assertEquals(expected.vehicleId(), actual.vehicleId());
        assertEquals(expected.period(), actual.period());
        assertEquals(expected.kind(), actual.kind());
        assertEquals(expected.status(), actual.status());
        assertEquals(expected.bookingCode(), actual.bookingCode());
        assertEquals(expected.reason(), actual.reason());
        assertEquals(expected.holdExpiresAt(), actual.holdExpiresAt());
        assertEquals(expected.createdAt(), actual.createdAt());
        assertEquals(expected.statusChangedAt(), actual.statusChangedAt());
    }
}
