package com.carrental.availability.application.service;

import com.carrental.availability.application.command.CompleteReservationCommand;
import com.carrental.availability.application.command.ConfirmReservationCommand;
import com.carrental.availability.application.command.MarkReservationInUseCommand;
import com.carrental.availability.application.command.ReleaseReservationCommand;
import com.carrental.availability.application.port.out.ReadReservationPolicyPort;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Kiểm điều phối chuyển trạng thái theo BR-103, BR-304, status-flow §2; SQL/transaction được kiểm riêng. */
class ReservationTransitionServiceTest {

    private static final String CODE = "KL-TRANS1";
    private static final Instant CREATED = Instant.parse("2030-01-01T00:00:00Z");
    private static final Instant NOW = CREATED.plusSeconds(60);
    private ReadReservationPort reads;
    private WriteReservationPort writes;
    private ReadReservationPolicyPort policies;
    private BusinessCodeGenerator codes;
    private Clock clock;
    private ReservationCommandService service;

    /** Tạo cổng giả để đếm lần đọc, ghi, lấy giờ và phát hiện việc đọc lại TTL/sinh mã sai. */
    @BeforeEach
    void setUp() {
        reads = mock(ReadReservationPort.class);
        writes = mock(WriteReservationPort.class);
        policies = mock(ReadReservationPolicyPort.class);
        codes = mock(BusinessCodeGenerator.class);
        clock = mock(Clock.class);
        when(clock.instant()).thenReturn(NOW, NOW.plusSeconds(600));
        service = new ReservationCommandService(writes, reads, policies, codes, clock);
    }

    /** Kiểm sáu cạnh hợp lệ dùng đúng trạng thái cũ/mới, một mốc Clock và không đọc chính sách. */
    @ParameterizedTest
    @MethodSource("validTransitions")
    void savesDomainTransitionWithSingleClockRead(String operation, ReservationStatus before, ReservationStatus after) {
        when(reads.loadAggregate(CODE)).thenReturn(Optional.of(reservation(before)));
        when(writes.updateStatus(CODE, before, after, NOW)).thenReturn(true);
        invoke(operation, CODE);
        verify(reads).loadAggregate(CODE);
        verify(clock).instant();
        verify(writes).updateStatus(CODE, before, after, NOW);
        verifyNoMoreInteractions(reads, writes, clock);
        verifyNoInteractions(policies, codes);
    }

    /** Kiểm mỗi thao tác từ chối command null trước khi chạm bất kỳ dependency nào. */
    @ParameterizedTest
    @ValueSource(strings = {"confirm", "release", "markInUse", "complete"})
    void rejectsNullCommandBeforeDependencies(String operation) {
        DomainException failure = assertThrows(DomainException.class, () -> invoke(operation, null));
        assertError(failure, ErrorCode.INVALID_REQUEST, DomainException.Category.INVALID_INPUT);
        verifyNoInteractions(reads, writes, clock, policies, codes);
    }

    /** Kiểm không tìm thấy mã reservation trả NOT_FOUND, không lấy giờ hoặc ghi. */
    @ParameterizedTest
    @ValueSource(strings = {"confirm", "release", "markInUse", "complete"})
    void reportsMissingReservationWithoutWriting(String operation) {
        when(reads.loadAggregate(CODE)).thenReturn(Optional.empty());
        DomainException failure = assertThrows(DomainException.class, () -> invoke(operation, CODE));
        assertError(failure, ErrorCode.RESERVATION_NOT_FOUND, DomainException.Category.NOT_FOUND);
        verify(reads).loadAggregate(CODE);
        verifyNoMoreInteractions(reads);
        verifyNoInteractions(writes, clock, policies, codes);
    }

    /** Kiểm toàn bộ cạnh không hợp lệ bị domain chặn trước cổng ghi, đúng nhóm RULE_VIOLATION. */
    @ParameterizedTest
    @MethodSource("invalidTransitions")
    void rejectsInvalidDomainTransitionWithoutWriting(String operation, ReservationStatus before) {
        when(reads.loadAggregate(CODE)).thenReturn(Optional.of(reservation(before)));
        DomainException failure = assertThrows(DomainException.class, () -> invoke(operation, CODE));
        assertError(failure, ErrorCode.RESERVATION_INVALID_STATUS_TRANSITION, DomainException.Category.RULE_VIOLATION);
        verify(reads).loadAggregate(CODE);
        verify(clock).instant();
        verifyNoMoreInteractions(reads, clock);
        verifyNoInteractions(writes, policies, codes);
    }

    /** Kiểm đúng hạn và sau hạn đều không được xác nhận; không tính lại TTL từ cấu hình. */
    @ParameterizedTest
    @ValueSource(longs = {0, 1})
    void rejectsConfirmationAtOrAfterStoredExpiry(long secondsAfterExpiry) {
        Reservation held = reservation(ReservationStatus.HELD);
        when(reads.loadAggregate(CODE)).thenReturn(Optional.of(held));
        when(clock.instant()).thenReturn(held.holdExpiresAt().plusSeconds(secondsAfterExpiry));
        DomainException failure = assertThrows(DomainException.class, () -> invoke("confirm", CODE));
        assertError(failure, ErrorCode.HOLD_EXPIRED, DomainException.Category.RULE_VIOLATION);
        verifyNoInteractions(writes, policies, codes);
    }

    /** Kiểm UPDATE không còn khớp trả CONFLICT, không thử lại, không đọc lại hoặc đổi thành thành công. */
    @ParameterizedTest
    @MethodSource("validTransitions")
    void reportsConcurrentChangeWithoutRetry(String operation, ReservationStatus before, ReservationStatus after) {
        when(reads.loadAggregate(CODE)).thenReturn(Optional.of(reservation(before)));
        when(writes.updateStatus(CODE, before, after, NOW)).thenReturn(false);
        DomainException failure = assertThrows(DomainException.class, () -> invoke(operation, CODE));
        assertError(failure, ErrorCode.RESERVATION_INVALID_STATUS_TRANSITION, DomainException.Category.CONFLICT);
        verify(reads).loadAggregate(CODE);
        verify(clock).instant();
        verify(writes).updateStatus(CODE, before, after, NOW);
        verifyNoMoreInteractions(reads, writes, clock);
        verifyNoInteractions(policies, codes);
    }

    /** Kiểm lỗi cổng đọc truyền nguyên, không bị đổi thành NOT_FOUND hoặc thực hiện ghi. */
    @ParameterizedTest
    @ValueSource(strings = {"confirm", "release", "markInUse", "complete"})
    void propagatesReadFailure(String operation) {
        IllegalStateException failure = new IllegalStateException("Read failed.");
        when(reads.loadAggregate(CODE)).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> invoke(operation, CODE)));
        verifyNoInteractions(writes, clock, policies, codes);
    }

    /** Kiểm lỗi cổng ghi truyền nguyên để transaction rollback, không bị dịch thành CONFLICT. */
    @ParameterizedTest
    @MethodSource("validTransitions")
    void propagatesWriteFailure(String operation, ReservationStatus before, ReservationStatus after) {
        IllegalStateException failure = new IllegalStateException("Write failed.");
        when(reads.loadAggregate(CODE)).thenReturn(Optional.of(reservation(before)));
        when(writes.updateStatus(CODE, before, after, NOW)).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> invoke(operation, CODE)));
        verify(writes).updateStatus(CODE, before, after, NOW);
        verifyNoMoreInteractions(writes);
        verifyNoInteractions(policies, codes);
    }

    /** Gọi bốn API kiểu tĩnh; null code trong helper này biểu diễn command null. */
    private void invoke(String operation, String code) {
        switch (operation) {
            case "confirm" -> service.confirm(code == null ? null : ConfirmReservationCommand.from(code));
            case "release" -> service.release(code == null ? null : ReleaseReservationCommand.from(code));
            case "markInUse" -> service.markInUse(code == null ? null : MarkReservationInUseCommand.from(code));
            case "complete" -> service.complete(code == null ? null : CompleteReservationCommand.from(code));
            default -> throw new IllegalArgumentException("Unknown test operation: " + operation);
        }
    }

    /** Tạo aggregate được khôi phục đúng hình dạng, để domain tự quyết định cạnh chuyển hợp lệ. */
    private static Reservation reservation(ReservationStatus status) {
        Instant start = Instant.parse("2030-10-01T03:00:00Z");
        Reservation held = Reservation.createHeld(CODE, 42L,
                ReservationPeriod.finite(start, start.plusSeconds(14400)), "booking-code",
                Duration.ofMinutes(90), CREATED);
        if (status == ReservationStatus.BLOCKED) {
            return Reservation.createBlocked(CODE, 42L, held.period(), ReservationKind.MAINTENANCE,
                    "Workshop", CREATED);
        }
        return Reservation.restore(CODE, held.vehicleId(), held.period(), held.kind(), status,
                held.bookingCode(), held.reason(), held.holdExpiresAt(), CREATED, CREATED);
    }

    /** Cung cấp toàn bộ sáu cạnh hợp lệ của bốn thao tác theo status-flow §2. */
    private static Stream<Arguments> validTransitions() {
        return Stream.of(
                Arguments.of("confirm", ReservationStatus.HELD, ReservationStatus.CONFIRMED),
                Arguments.of("release", ReservationStatus.HELD, ReservationStatus.RELEASED),
                Arguments.of("release", ReservationStatus.CONFIRMED, ReservationStatus.RELEASED),
                Arguments.of("markInUse", ReservationStatus.CONFIRMED, ReservationStatus.IN_USE),
                Arguments.of("complete", ReservationStatus.IN_USE, ReservationStatus.COMPLETED),
                Arguments.of("complete", ReservationStatus.BLOCKED, ReservationStatus.COMPLETED)
        );
    }

    /** Sinh các tổ hợp còn lại từ toàn bộ trạng thái để khóa chặt việc gọi đúng hành vi domain. */
    private static Stream<Arguments> invalidTransitions() {
        return Stream.of("confirm", "release", "markInUse", "complete")
                .flatMap(operation -> Arrays.stream(ReservationStatus.values())
                        .filter(status -> switch (operation) {
                            case "confirm" -> status != ReservationStatus.HELD;
                            case "release" -> status != ReservationStatus.HELD && status != ReservationStatus.CONFIRMED;
                            case "markInUse" -> status != ReservationStatus.CONFIRMED;
                            case "complete" -> status != ReservationStatus.IN_USE && status != ReservationStatus.BLOCKED;
                            default -> throw new IllegalArgumentException("Unknown test operation.");
                        }).map(status -> Arguments.of(operation, status)));
    }

    /** Đối chiếu mã và nhóm lỗi độc lập, không dựa vào chuỗi thông báo. */
    private static void assertError(DomainException failure, ErrorCode code, DomainException.Category category) {
        assertEquals(code, failure.errorCode());
        assertEquals(category, failure.category());
    }
}
