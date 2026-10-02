package com.carrental.availability.application.service;

import com.carrental.PostgresTestConfiguration;
import com.carrental.availability.application.command.CompleteReservationCommand;
import com.carrental.availability.application.command.ConfirmReservationCommand;
import com.carrental.availability.application.command.HoldReservationCommand;
import com.carrental.availability.application.command.MarkReservationInUseCommand;
import com.carrental.availability.application.command.ReleaseReservationCommand;
import com.carrental.availability.application.port.in.CompleteReservationUseCase;
import com.carrental.availability.application.port.in.ConfirmReservationUseCase;
import com.carrental.availability.application.port.in.HoldReservationUseCase;
import com.carrental.availability.application.port.in.MarkReservationInUseUseCase;
import com.carrental.availability.application.port.in.ReleaseReservationUseCase;
import com.carrental.availability.application.port.out.ReadReservationPort;
import com.carrental.availability.application.port.out.WriteReservationPort;
import com.carrental.availability.domain.Reservation;
import com.carrental.availability.domain.ReservationKind;
import com.carrental.availability.domain.ReservationPeriod;
import com.carrental.availability.domain.ReservationStatus;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Kiểm service qua proxy Spring, domain, port, SQL và PostgreSQL thật theo status-flow §2.
 *
 * <p>Chỉ thay Clock để kiểm biên BR-103. Các use case, adapter và transaction đều thật.
 * Test mặc định rollback; các ca commit riêng được cô lập bằng context đóng sau lớp.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class ReservationTransitionIntegrationTest {

    private static final Instant CREATED = Instant.parse("2030-01-01T00:00:00Z");
    private static final Instant NOW = CREATED.plusSeconds(60);
    private static final Instant START = Instant.parse("2030-10-01T03:00:00Z");
    private static final String CODE = "KL-LIFE01";
    private static final long VEHICLE_ID = 9_000_000_000_701L;

    @Autowired
    private ConfirmReservationUseCase confirm;
    @Autowired
    private ReleaseReservationUseCase release;
    @Autowired
    private MarkReservationInUseUseCase markInUse;
    @Autowired
    private CompleteReservationUseCase complete;
    @Autowired
    private HoldReservationUseCase hold;
    @Autowired
    private WriteReservationPort writes;
    @Autowired
    private ReadReservationPort reads;
    @MockitoBean(name = "applicationClock")
    private Clock clock;

    /** Cung cấp giờ trước hạn của bản ghi đã lưu, không phụ thuộc đồng hồ máy chạy test. */
    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW);
    }

    /** Kiểm toàn bộ cạnh qua proxy và đối chiếu mọi thuộc tính, gồm khóa vận hành vô hạn. */
    @ParameterizedTest
    @MethodSource("validTransitions")
    void persistsTransitionWithoutChangingFrozenFields(String operation, Reservation before, Reservation expected) {
        writes.insert(before).orElseThrow();
        invoke(operation, before.code());
        assertStored(expected);
    }

    /** Kiểm đúng/sau hạn không xác nhận được và database vẫn lưu HELD với hạn cũ. */
    @ParameterizedTest
    @ValueSource(longs = {0, 1})
    void expiredConfirmationLeavesStoredHoldUntouched(long secondsAfterExpiry) {
        Reservation before = held(CODE, VEHICLE_ID);
        writes.insert(before).orElseThrow();
        when(clock.instant()).thenReturn(before.holdExpiresAt().plusSeconds(secondsAfterExpiry));
        DomainException failure = assertThrows(DomainException.class,
                () -> confirm.confirm(ConfirmReservationCommand.from(CODE)));
        assertEquals(ErrorCode.HOLD_EXPIRED, failure.errorCode());
        assertEquals(DomainException.Category.RULE_VIOLATION, failure.category());
        assertStored(before);
    }

    /** Kiểm khóa TTL 90 phút vẫn xác nhận được sau một giờ, sát hạn đã lưu, không dùng lại mặc định. */
    @Test
    void confirmsAgainstStoredExpiryInsteadOfCurrentPolicy() {
        Reservation before = held(CODE, VEHICLE_ID);
        writes.insert(before).orElseThrow();
        Instant nearExpiry = before.holdExpiresAt().minusNanos(1000);
        when(clock.instant()).thenReturn(nearExpiry);
        confirm.confirm(ConfirmReservationCommand.from(CODE));
        assertStored(before.confirm(nearExpiry));
    }

    /** Kiểm bốn proxy trả đúng RESERVATION_NOT_FOUND khi SQL không tìm thấy mã. */
    @ParameterizedTest
    @ValueSource(strings = {"confirm", "release", "markInUse", "complete"})
    void reportsMissingCodeThroughRealReadAdapter(String operation) {
        DomainException failure = assertThrows(DomainException.class, () -> invoke(operation, "KL-NONE01"));
        assertEquals(ErrorCode.RESERVATION_NOT_FOUND, failure.errorCode());
        assertEquals(DomainException.Category.NOT_FOUND, failure.category());
    }

    /** Kiểm chuyển đúng một mã reservation, không vô tình sửa khóa gia hạn có cùng booking_code (BR-427). */
    @Test
    void changesOnlyRequestedReservationWhenBookingCodeIsShared() {
        Reservation original = held(CODE, VEHICLE_ID);
        Reservation extension = Reservation.createHeld("KL-LIFE02", VEHICLE_ID,
                ReservationPeriod.finite(original.period().endExclusive(),
                        original.period().endExclusive().plusSeconds(7200)),
                original.bookingCode(), Duration.ofMinutes(90), CREATED);
        writes.insert(original).orElseThrow();
        writes.insert(extension).orElseThrow();
        release.release(ReleaseReservationCommand.from(CODE));
        assertStored(original.release(NOW));
        assertStored(extension);
    }

    /** Kiểm cả bốn thao tác tham gia transaction ngoài; rollback ngoài hủy cả seed và chuyển trạng thái. */
    @ParameterizedTest
    @ValueSource(strings = {"confirm", "release", "markInUse", "complete"})
    void rollsBackWithCallerTransaction(String operation) {
        Reservation before = initialFor(operation, CODE, VEHICLE_ID);
        writes.insert(before).orElseThrow();
        invoke(operation, CODE);
        assertStored(changedBy(operation, before));
        TestTransaction.flagForRollback();
        TestTransaction.end();
        TestTransaction.start();
        assertTrue(reads.loadAggregate(CODE).isEmpty());
    }

    /** Kiểm mỗi proxy tự mở và commit transaction khi không có transaction bên gọi. */
    @ParameterizedTest
    @ValueSource(strings = {"confirm", "release", "markInUse", "complete"})
    void commitsWhenCalledWithoutOuterTransaction(String operation) {
        int index = switch (operation) {
            case "confirm" -> 1;
            case "release" -> 2;
            case "markInUse" -> 3;
            case "complete" -> 4;
            default -> throw new IllegalArgumentException("Unknown test operation.");
        };
        Reservation before = initialFor(operation, "KL-AUTO0" + index, VEHICLE_ID + index);
        writes.insert(before).orElseThrow();
        TestTransaction.flagForCommit();
        TestTransaction.end();
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        invoke(operation, before.code());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        TestTransaction.start();
        assertStored(changedBy(operation, before));
    }

    /** Kiểm COMPLETED còn chặn phần đệm, nhưng cho phép giữ chỗ liền kề sau cận trên theo BR-109. */
    @Test
    void completionPreservesBufferAndAllowsOnlyNonOverlappingHold() {
        Reservation before = initialFor("complete", CODE, VEHICLE_ID);
        writes.insert(before).orElseThrow();
        complete.complete(CompleteReservationCommand.from(CODE));
        assertStored(before.complete(NOW));
        Instant boundary = before.period().endExclusive();
        assertNotNull(hold.hold(HoldReservationCommand.from(VEHICLE_ID, boundary,
                boundary.plusSeconds(3600), Duration.ZERO, "adjacent-booking")));
        DomainException failure = assertThrows(DomainException.class,
                () -> hold.hold(HoldReservationCommand.from(VEHICLE_ID,
                        boundary.minusSeconds(3600), boundary, Duration.ZERO, "buffer-overlap-booking")));
        assertEquals(ErrorCode.VEHICLE_NOT_AVAILABLE, failure.errorCode());
        assertEquals(DomainException.Category.CONFLICT, failure.category());
    }

    /** Kiểm nhả HELD/CONFIRMED cho phép giữ lại đúng xe và khoảng vừa nhả theo BR-103, BR-304. */
    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"HELD", "CONFIRMED"})
    void releaseAllowsNewHoldForSamePeriod(ReservationStatus status) {
        Reservation original = held(CODE, VEHICLE_ID);
        Reservation before = status == ReservationStatus.HELD ? original : original.confirm(CREATED.plusSeconds(10));
        writes.insert(before).orElseThrow();
        release.release(ReleaseReservationCommand.from(CODE));
        var ref = hold.hold(HoldReservationCommand.from(VEHICLE_ID,
                before.period().startInclusive(), before.period().endExclusive(), Duration.ZERO, "replacement-booking"));
        assertTrue(ref.id() > 0);
        assertNotEquals(CODE, ref.code());
        assertStored(before.release(NOW));
        assertEquals(ReservationStatus.HELD, reads.loadAggregate(ref.code()).orElseThrow().status());
    }

    /** Gọi đúng use case Spring đã tiêm, không gọi trực tiếp service để tránh bỏ qua transaction proxy. */
    private void invoke(String operation, String code) {
        switch (operation) {
            case "confirm" -> confirm.confirm(ConfirmReservationCommand.from(code));
            case "release" -> release.release(ReleaseReservationCommand.from(code));
            case "markInUse" -> markInUse.markInUse(MarkReservationInUseCommand.from(code));
            case "complete" -> complete.complete(CompleteReservationCommand.from(code));
            default -> throw new IllegalArgumentException("Unknown test operation: " + operation);
        }
    }

    /** Tạo khóa với TTL 90 phút đã lưu, khác mặc định hiện tại để phát hiện tính lại TTL sai. */
    private static Reservation held(String code, long vehicleId) {
        return Reservation.createHeld(code, vehicleId,
                ReservationPeriod.finite(START, START.plusSeconds(14400)),
                "shared-booking", Duration.ofMinutes(90), CREATED);
    }

    /** Chuẩn bị trạng thái nguồn hợp lệ cho từng thao tác bằng chính domain. */
    private static Reservation initialFor(String operation, String code, long vehicleId) {
        Reservation original = held(code, vehicleId);
        return switch (operation) {
            case "confirm", "release" -> original;
            case "markInUse" -> original.confirm(CREATED.plusSeconds(10));
            case "complete" -> original.confirm(CREATED.plusSeconds(10)).markInUse(CREATED.plusSeconds(20));
            default -> throw new IllegalArgumentException("Unknown test operation: " + operation);
        };
    }

    /** Tạo kỳ vọng từ hành vi domain tại cùng mốc Clock; SQL phải lưu đúng kết quả đó. */
    private static Reservation changedBy(String operation, Reservation before) {
        return switch (operation) {
            case "confirm" -> before.confirm(NOW);
            case "release" -> before.release(NOW);
            case "markInUse" -> before.markInUse(NOW);
            case "complete" -> before.complete(NOW);
            default -> throw new IllegalArgumentException("Unknown test operation: " + operation);
        };
    }

    /** Sáu cạnh cùng biến thể compliance vô hạn, giữ nguyên reason và period. */
    private static Stream<Arguments> validTransitions() {
        Reservation held = held(CODE, VEHICLE_ID);
        Reservation confirmed = held.confirm(CREATED.plusSeconds(10));
        Reservation inUse = confirmed.markInUse(CREATED.plusSeconds(20));
        Reservation blocked = Reservation.createBlocked(CODE, VEHICLE_ID, held.period(),
                ReservationKind.MAINTENANCE, " Workshop ", CREATED);
        Reservation unbounded = Reservation.createBlocked(CODE, VEHICLE_ID,
                ReservationPeriod.unboundedFrom(START), ReservationKind.COMPLIANCE_HOLD, "Expired document", CREATED);
        return Stream.of(
                Arguments.of("confirm", held, held.confirm(NOW)),
                Arguments.of("release", held, held.release(NOW)),
                Arguments.of("release", confirmed, confirmed.release(NOW)),
                Arguments.of("markInUse", confirmed, confirmed.markInUse(NOW)),
                Arguments.of("complete", inUse, inUse.complete(NOW)),
                Arguments.of("complete", blocked, blocked.complete(NOW)),
                Arguments.of("complete", unbounded, unbounded.complete(NOW))
        );
    }

    /** Đối chiếu toàn bộ dữ liệu để bắt việc đổi nhầm trường đã đóng băng. */
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
