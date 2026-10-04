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
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Kiểm UPDATE thật chỉ thay trạng thái và mốc đổi theo status-flow §2, BR-103, BR-109, BR-116. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
@Transactional
class ReservationStatusWriteIntegrationTest {

    private static final Instant CREATED = Instant.parse("2030-01-01T00:00:00Z");
    private static final Instant CHANGED = CREATED.plusSeconds(60);
    private static final Instant START = Instant.parse("2030-10-01T03:00:00Z");
    private static final long VEHICLE_ID = 9_000_000_000_501L;

    @Autowired
    private WriteReservationPort writes;
    @Autowired
    private ReadReservationPort reads;

    /**
     * Kiểm các cạnh hợp lệ, gồm khóa vận hành/vô hạn; giữ nguyên mọi trường ngoài trạng thái và mốc đổi.
     *
     * @param before aggregate trước chuyển
     * @param after kết quả do domain quyết định
     */
    @ParameterizedTest
    @MethodSource("transitions")
    void updatesOnlyStatusAndChangeTimestamp(Reservation before, Reservation after) {
        writes.insert(before).orElseThrow();
        assertTrue(writes.updateStatus(before.code(), before.status(), after.status(), after.statusChangedAt()));
        assertStored(after);
        Reservation actual = reads.loadAggregate(before.code()).orElseThrow();
        assertEquals(before.period(), actual.period());
        assertEquals(before.createdAt(), actual.createdAt());
        assertEquals(before.holdExpiresAt(), actual.holdExpiresAt());
    }

    /** Kiểm trạng thái cũ sai không đổi bản ghi, kể cả status_changed_at. */
    @Test
    void staleExpectedStatusLeavesRowUntouched() {
        Reservation original = held();
        writes.insert(original).orElseThrow();
        assertFalse(writes.updateStatus(original.code(), ReservationStatus.CONFIRMED,
                ReservationStatus.IN_USE, CHANGED));
        assertStored(original);
    }

    /** Kiểm mã không tồn tại trả false và không tác động khóa khác. */
    @Test
    void missingCodeLeavesOtherRowUntouched() {
        Reservation original = held();
        writes.insert(original).orElseThrow();
        assertFalse(writes.updateStatus("KL-NONE01", ReservationStatus.HELD, ReservationStatus.RELEASED, CHANGED));
        assertStored(original);
    }

    /** Kiểm gọi lại với trạng thái cũ không ghi đè mốc thay đổi đã được lưu. */
    @Test
    void repeatedOldTransitionDoesNotOverwriteTimestamp() {
        Reservation original = held();
        writes.insert(original).orElseThrow();
        Reservation confirmed = original.confirm(CHANGED);
        assertTrue(writes.updateStatus(original.code(), original.status(), confirmed.status(), CHANGED));
        assertFalse(writes.updateStatus(original.code(), original.status(), confirmed.status(), CHANGED.plusSeconds(20)));
        assertStored(confirmed);
    }

    /** Kiểm UPDATE không tự commit: rollback ngoài hủy cả chèn và cập nhật trong transaction đó. */
    @Test
    void insertAndUpdateRollBackWithCaller() {
        Reservation original = held();
        writes.insert(original).orElseThrow();
        assertTrue(writes.updateStatus(original.code(), original.status(), ReservationStatus.RELEASED, CHANGED));
        assertStored(original.release(CHANGED));
        TestTransaction.flagForRollback();
        TestTransaction.end();
        TestTransaction.start();
        assertTrue(reads.loadAggregate(original.code()).isEmpty());
    }

    /** Tạo khóa thuê chứa cả phần đệm đã cộng trong khoảng để phát hiện UPDATE co khoảng. */
    private static Reservation held() {
        return Reservation.createHeld("KL-STAT01", VEHICLE_ID,
                ReservationPeriod.finite(START, START.plusSeconds(14400)),
                "status-booking", Duration.ofHours(1), CREATED);
    }

    /** Cung cấp sáu cạnh hợp lệ; khóa giấy tờ không có cạnh chuyển trạng thái theo BR-015. */
    private static Stream<Arguments> transitions() {
        Reservation held = held();
        Reservation confirmed = held.confirm(CREATED.plusSeconds(10));
        Reservation inUse = confirmed.markInUse(CREATED.plusSeconds(20));
        Reservation blocked = Reservation.createBlocked("KL-STAT01", VEHICLE_ID, held.period(),
                ReservationKind.MAINTENANCE, "  Workshop  ", CREATED);
        return Stream.of(
                Arguments.of(held, held.confirm(CHANGED)),
                Arguments.of(held, held.release(CHANGED)),
                Arguments.of(confirmed, confirmed.release(CHANGED)),
                Arguments.of(confirmed, confirmed.markInUse(CHANGED)),
                Arguments.of(inUse, inUse.complete(CHANGED)),
                Arguments.of(blocked, blocked.complete(CHANGED))
        );
    }

    /** Đọc lại qua port và đối chiếu mọi thuộc tính của aggregate. */
    private void assertStored(Reservation expected) {
        Reservation actual = reads.loadAggregate(expected.code()).orElseThrow();
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
