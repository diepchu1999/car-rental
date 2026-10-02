package com.carrental.availability.application.service;

import com.carrental.PostgresTestConfiguration;
import com.carrental.availability.api.ReservationRef;
import com.carrental.availability.application.command.BlockReservationCommand;
import com.carrental.availability.application.command.HoldReservationCommand;
import com.carrental.availability.application.port.in.BlockReservationUseCase;
import com.carrental.availability.application.port.in.HoldReservationUseCase;
import com.carrental.availability.application.port.out.ReadReservationPort;
import com.carrental.availability.application.port.out.WriteReservationPort;
import com.carrental.availability.domain.Reservation;
import com.carrental.availability.domain.ReservationKind;
import com.carrental.availability.domain.ReservationPeriod;
import com.carrental.availability.domain.ReservationStatus;
import com.carrental.shared.code.BusinessCodeGenerator;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Kiểm luồng BLOCKED qua Spring, domain, adapter và PostgreSQL theo BR-104, ADR-0005.
 *
 * <p>Chỉ cố định mã và Clock; không mô phỏng lưu trữ, transaction hoặc ràng buộc loại trừ.
 * Không cần xe thật từ module vehicle. Mỗi test rollback trong database Testcontainers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
@Transactional
class ReservationBlockIntegrationTest {

    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    private static final Instant START = Instant.parse("2030-10-01T03:00:00Z");
    private static final Instant END = START.plusSeconds(7200);
    private static final long VEHICLE_ID = 9_000_000_000_801L;

    @Autowired
    private BlockReservationUseCase block;
    @Autowired
    private HoldReservationUseCase hold;
    @Autowired
    private ReadReservationPort reads;
    @Autowired
    private WriteReservationPort writes;
    @MockitoBean
    private BusinessCodeGenerator codeGenerator;
    @MockitoBean(name = "applicationClock")
    private Clock clock;

    /** Chuẩn bị mã xác định để đếm thử lại; mốc tạo và đổi trạng thái phải lấy từ cùng Clock. */
    @BeforeEach
    void setUp() {
        when(codeGenerator.generate("KL")).thenReturn("KL-BLOC01", "KL-BLOC02", "KL-BLOC03");
        when(clock.instant()).thenReturn(NOW);
    }

    /** Kiểm năm loại khóa lưu trực tiếp BLOCKED với lý do, đúng khoảng và không TTL/mã đơn. */
    @ParameterizedTest
    @EnumSource(value = ReservationKind.class, names = "RENTAL", mode = EnumSource.Mode.EXCLUDE)
    void persistsOperationalKindAndExactPeriod(ReservationKind kind) {
        BlockReservationCommand command = command(kind, END, "  Workshop  ");
        ReservationRef ref = block.block(command);
        assertStored(command, ref);
        verify(codeGenerator).generate("KL");
        verify(clock).instant();
    }

    /** Kiểm reason null/rỗng/trắng được lưu nguyên theo hợp đồng cột nullable đã duyệt. */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " "})
    void preservesNullableAndBlankReason(String reason) {
        BlockReservationCommand command = command(ReservationKind.INSPECTION, END, reason);
        assertStored(command, block.block(command));
    }

    /** Kiểm mọi trạng thái còn chặn đều khiến block thất bại đúng lỗi và không thử thêm mã. */
    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = "RELEASED", mode = EnumSource.Mode.EXCLUDE)
    void rejectsExistingBlockingReservationWithoutRetry(ReservationStatus status) {
        Reservation existing = status == ReservationStatus.BLOCKED
                ? Reservation.createBlocked("KL-SEED01", VEHICLE_ID, ReservationPeriod.finite(START, END),
                    ReservationKind.MAINTENANCE, null, NOW)
                : Reservation.restore("KL-SEED01", VEHICLE_ID, ReservationPeriod.finite(START, END),
                    ReservationKind.RENTAL, status, "existing-booking", null, NOW.plusSeconds(3600), NOW, NOW);
        writes.insert(existing).orElseThrow();
        assertVehicleBusy(() -> block.block(command(ReservationKind.TRANSFER, END, "Transfer")));
        verify(codeGenerator).generate("KL");
        verifyNoMoreInteractions(codeGenerator);
    }

    /** Kiểm một khóa vận hành đã lưu chặn hold chồng một phần, không chỉ chặn đúng hai khoảng bằng nhau. */
    @ParameterizedTest
    @EnumSource(value = ReservationKind.class, names = "RENTAL", mode = EnumSource.Mode.EXCLUDE)
    void operationalBlockRejectsOverlappingHold(ReservationKind kind) {
        block.block(command(kind, END, null));
        assertVehicleBusy(() -> hold.hold(HoldReservationCommand.from(VEHICLE_ID,
                START.plusSeconds(3600), END.plusSeconds(3600), Duration.ZERO, "overlapping-booking")));
        verify(codeGenerator, times(2)).generate("KL");
        verifyNoMoreInteractions(codeGenerator);
    }

    /** Kiểm khóa compliance vô hạn chặn đúng mốc bắt đầu và cả yêu cầu nhiều năm sau theo BR-015. */
    @ParameterizedTest
    @ValueSource(longs = {0, 86400, 315360000})
    void unboundedComplianceBlocksFutureHolds(long secondsAfterStart) {
        BlockReservationCommand command = command(ReservationKind.COMPLIANCE_HOLD, null, "Expired document");
        assertStored(command, block.block(command));
        Instant rentalStart = START.plusSeconds(secondsAfterStart);
        assertVehicleBusy(() -> hold.hold(HoldReservationCommand.from(VEHICLE_ID,
                rentalStart, rentalStart.plusSeconds(3600), Duration.ZERO, "future-booking")));
    }

    /** Kiểm [) và không tự cộng đệm: hai khóa vận hành liền kề đều ghi được. */
    @Test
    void adjacentOperationalPeriodsBothSucceed() {
        BlockReservationCommand first = command(ReservationKind.MAINTENANCE, END, "Workshop");
        BlockReservationCommand second = BlockReservationCommand.from(VEHICLE_ID, END, END.plusSeconds(3600),
                ReservationKind.TRANSFER, "Transfer");
        ReservationRef firstRef = block.block(first);
        ReservationRef secondRef = block.block(second);
        assertNotEquals(firstRef, secondRef);
        assertStored(first, firstRef);
        assertStored(second, secondRef);
    }

    /** Kiểm RELEASED không còn cản việc tạo khóa vận hành trên cùng khoảng. */
    @Test
    void releasedReservationDoesNotPreventBlock() {
        Reservation existing = Reservation.createHeld("KL-SEED01", VEHICLE_ID,
                ReservationPeriod.finite(START, END), "cancelled-booking", Duration.ofHours(1), NOW)
                .release(NOW.plusSeconds(10));
        writes.insert(existing).orElseThrow();
        BlockReservationCommand command = command(ReservationKind.MAINTENANCE, END, null);
        assertStored(command, block.block(command));
        assertEquals(ReservationStatus.RELEASED, reads.loadAggregate(existing.code()).orElseThrow().status());
    }

    /** Kiểm trùng mã thật chỉ bỏ qua uq_reservation_code và thử mã kế tiếp, không làm hỏng transaction. */
    @Test
    void retriesActualCodeCollision() {
        writes.insert(Reservation.createBlocked("KL-BLOC01", VEHICLE_ID + 1,
                ReservationPeriod.finite(START, END), ReservationKind.MAINTENANCE, "Existing", NOW)).orElseThrow();
        BlockReservationCommand command = command(ReservationKind.COMPLIANCE_HOLD, null, "New block");
        ReservationRef ref = block.block(command);
        assertEquals("KL-BLOC02", ref.code());
        assertStored(command, ref);
        assertEquals(VEHICLE_ID + 1, reads.loadAggregate("KL-BLOC01").orElseThrow().vehicleId());
        verify(codeGenerator, times(2)).generate("KL");
        verifyNoMoreInteractions(codeGenerator);
    }

    /** Kiểm block không commit độc lập: rollback transaction bên gọi xóa bản ghi vừa tạo. */
    @Test
    void rollsBackWithCallerTransaction() {
        BlockReservationCommand command = command(ReservationKind.TRANSFER, END, "Transfer");
        ReservationRef ref = block.block(command);
        assertStored(command, ref);
        TestTransaction.flagForRollback();
        TestTransaction.end();
        TestTransaction.start();
        assertTrue(reads.loadAggregate(ref.code()).isEmpty());
    }

    /** Tạo lệnh với khoảng vận hành nguyên gốc, không chứa đệm thuê xe. */
    private static BlockReservationCommand command(ReservationKind kind, Instant end, String reason) {
        return BlockReservationCommand.from(VEHICLE_ID, START, end, kind, reason);
    }

    /** Chỉ VEHICLE_NOT_AVAILABLE/CONFLICT được tính là từ chối đúng nguyên nhân. */
    private static void assertVehicleBusy(Runnable operation) {
        DomainException failure = assertThrows(DomainException.class, operation::run);
        assertEquals(ErrorCode.VEHICLE_NOT_AVAILABLE, failure.errorCode());
        assertEquals(DomainException.Category.CONFLICT, failure.category());
    }

    /** Đối chiếu toàn bộ trường đã lưu, kể cả cận trên null và lý do nguyên văn. */
    private void assertStored(BlockReservationCommand command, ReservationRef ref) {
        assertTrue(ref.id() > 0);
        Reservation actual = reads.loadAggregate(ref.code()).orElseThrow();
        assertEquals(ref.code(), actual.code());
        assertEquals(command.vehicleId(), actual.vehicleId());
        assertEquals(command.period(), actual.period());
        assertEquals(command.kind(), actual.kind());
        assertEquals(command.reason(), actual.reason());
        assertEquals(ReservationStatus.BLOCKED, actual.status());
        assertNull(actual.bookingCode());
        assertNull(actual.holdExpiresAt());
        assertEquals(NOW, actual.createdAt());
        assertEquals(NOW, actual.statusChangedAt());
    }
}
