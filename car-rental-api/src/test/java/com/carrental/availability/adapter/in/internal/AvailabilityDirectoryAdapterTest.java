package com.carrental.availability.adapter.in.internal;

import com.carrental.availability.api.AvailabilityDirectory;
import com.carrental.availability.api.BlockKind;
import com.carrental.availability.api.Period;
import com.carrental.availability.api.ReservationRef;
import com.carrental.availability.application.command.BlockReservationCommand;
import com.carrental.availability.application.command.CompleteReservationCommand;
import com.carrental.availability.application.command.ConfirmReservationCommand;
import com.carrental.availability.application.command.HoldReservationCommand;
import com.carrental.availability.application.command.MarkReservationInUseCommand;
import com.carrental.availability.application.command.MoveComplianceHoldStartCommand;
import com.carrental.availability.application.command.ReleaseReservationCommand;
import com.carrental.availability.application.port.in.BlockReservationUseCase;
import com.carrental.availability.application.port.in.CompleteReservationUseCase;
import com.carrental.availability.application.port.in.ConfirmReservationUseCase;
import com.carrental.availability.application.port.in.HoldReservationUseCase;
import com.carrental.availability.application.port.in.ListBusyVehiclesUseCase;
import com.carrental.availability.application.port.in.MarkReservationInUseUseCase;
import com.carrental.availability.application.port.in.MoveComplianceHoldStartUseCase;
import com.carrental.availability.application.port.in.ReleaseReservationUseCase;
import com.carrental.availability.application.query.ListBusyVehiclesQuery;
import com.carrental.availability.domain.ReservationKind;
import com.carrental.availability.domain.ReservationPeriod;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Kiểm lớp nối API chỉ ánh xạ và ủy quyền, không lặp lại nghiệp vụ hoặc nuốt lỗi. */
class AvailabilityDirectoryAdapterTest {

    private static final Instant START = Instant.parse("2030-10-02T03:00:00Z");
    private static final Instant END = START.plusSeconds(7200);
    private static final Period PERIOD = new Period(START, END);
    private HoldReservationUseCase holds;
    private BlockReservationUseCase blocks;
    private ConfirmReservationUseCase confirmations;
    private ReleaseReservationUseCase releases;
    private MarkReservationInUseUseCase departures;
    private CompleteReservationUseCase completions;
    private MoveComplianceHoldStartUseCase complianceMoves;
    private ListBusyVehiclesUseCase queries;
    private AvailabilityDirectory directory;

    /** Tạo adapter với các cổng riêng để phát hiện gọi nhầm hoặc đọc trước khi giữ chỗ. */
    @BeforeEach
    void setUp() {
        holds = mock(HoldReservationUseCase.class);
        blocks = mock(BlockReservationUseCase.class);
        confirmations = mock(ConfirmReservationUseCase.class);
        releases = mock(ReleaseReservationUseCase.class);
        departures = mock(MarkReservationInUseUseCase.class);
        completions = mock(CompleteReservationUseCase.class);
        complianceMoves = mock(MoveComplianceHoldStartUseCase.class);
        queries = mock(ListBusyVehiclesUseCase.class);
        directory = new AvailabilityDirectoryAdapter(holds, blocks, confirmations, releases,
                departures, completions, queries, complianceMoves);
    }

    /** Kiểm giữ nguyên khoảng chưa cộng đệm, bookingCode, trả đúng ref và không gọi cổng tra cứu. */
    @Test
    void mapsHoldWithoutApplyingBufferOrCheckingAvailability() {
        var command = HoldReservationCommand.from(41L, START, END, Duration.ofHours(2), " booking-code ");
        var ref = new ReservationRef(91L, "KL-ABC123");
        when(holds.hold(command)).thenReturn(ref);
        assertSame(ref, directory.hold(41L, PERIOD, Duration.ofHours(2), " booking-code "));
        verify(holds).hold(command);
        assertExactlyOneInvocation();
    }

    /** Kiểm ánh xạ đủ năm BlockKind, giữ nguyên khoảng và lý do có khoảng trắng. */
    @ParameterizedTest
    @EnumSource(BlockKind.class)
    void mapsEveryBlockKindAndPreservesReason(BlockKind kind) {
        var period = kind == BlockKind.COMPLIANCE_HOLD ? new Period(START, null) : PERIOD;
        var command = BlockReservationCommand.from(41L, START, period.endExclusive(),
                ReservationKind.valueOf(kind.name()), " Scheduled work ");
        var ref = new ReservationRef(92L, "KL-DEF456");
        when(blocks.block(command)).thenReturn(ref);
        assertSame(ref, directory.block(41L, period, kind, " Scheduled work "));
        verify(blocks).block(command);
        assertExactlyOneInvocation();
    }

    /** Kiểm compliance giữ cận trên null và lý do null, không thay bằng ngày giả. */
    @Test
    void preservesUnboundedCompliance() {
        directory.block(41L, new Period(START, null), BlockKind.COMPLIANCE_HOLD, null);
        verify(blocks).block(BlockReservationCommand.from(41L, START, null,
                ReservationKind.COMPLIANCE_HOLD, null));
        assertExactlyOneInvocation();
    }

    /** Kiểm cả bốn thao tác chuyển trạng thái gọi đúng cổng và giữ nguyên mã reservation. */
    @ParameterizedTest
    @ValueSource(strings = {"confirm", "release", "markInUse", "complete"})
    void delegatesTransitionByExactReservationCode(String operation) {
        String code = " KL-ABC123 ";
        switch (operation) {
            case "confirm" -> {
                directory.confirm(code);
                verify(confirmations).confirm(ConfirmReservationCommand.from(code));
            }
            case "release" -> {
                directory.release(code);
                verify(releases).release(ReleaseReservationCommand.from(code));
            }
            case "markInUse" -> {
                directory.markInUse(code);
                verify(departures).markInUse(MarkReservationInUseCommand.from(code));
            }
            case "complete" -> {
                directory.complete(code);
                verify(completions).complete(CompleteReservationCommand.from(code));
            }
            default -> throw new AssertionError("Unknown operation: " + operation);
        }
        assertExactlyOneInvocation();
    }

    /** Kiểm dời mốc giữ nguyên mã và Instant, chỉ ủy quyền đúng use case BR-015. */
    @Test
    void delegatesComplianceMoveWithoutChangingInputs() {
        directory.moveComplianceHoldStart(" KL-MOVE01 ", END);
        verify(complianceMoves).moveComplianceHoldStart(MoveComplianceHoldStartCommand.from(" KL-MOVE01 ", END));
        assertExactlyOneInvocation();
    }

    /** Kiểm luồng đọc chuyển đủ khoảng, đệm, ứng viên; không đọc hoặc ghi thêm. */
    @Test
    void delegatesBusyQueryWithoutApplyingBuffer() {
        var query = ListBusyVehiclesQuery.from(START, END, Duration.ofHours(1), List.of(41L, 42L, 41L));
        Set<Long> expected = Set.of(42L);
        when(queries.listBusyVehicleIds(query)).thenReturn(expected);
        assertSame(expected, directory.findBusyVehicleIds(PERIOD, Duration.ofHours(1), List.of(41L, 42L, 41L)));
        verify(queries).listBusyVehicleIds(query);
        assertEquals(ReservationPeriod.finite(START, END), query.rentalPeriod());
        assertExactlyOneInvocation();
    }

    /** Kiểm lỗi đầu vào được báo INVALID_REQUEST trước khi bất kỳ use case nào được gọi. */
    @ParameterizedTest
    @MethodSource("invalidCalls")
    void rejectsInvalidInputBeforeDelegation(Consumer<AvailabilityDirectory> call) {
        var failure = assertThrows(DomainException.class, () -> call.accept(directory));
        assertEquals(ErrorCode.INVALID_REQUEST, failure.errorCode());
        verifyNoInteractions(dependencies());
    }

    /** Kiểm lỗi nghiệp vụ và lỗi hạ tầng của từng cổng đều truyền đúng exception, không retry. */
    @ParameterizedTest
    @MethodSource("failedCalls")
    void preservesFailuresFromEveryUseCase(String operation, RuntimeException failure) {
        switch (operation) {
            case "hold" -> {
                when(holds.hold(any())).thenThrow(failure);
                assertSame(failure, assertThrows(RuntimeException.class,
                        () -> directory.hold(41L, PERIOD, Duration.ZERO, "booking")));
            }
            case "block" -> {
                when(blocks.block(any())).thenThrow(failure);
                assertSame(failure, assertThrows(RuntimeException.class,
                        () -> directory.block(41L, PERIOD, BlockKind.MAINTENANCE, null)));
            }
            case "confirm" -> {
                doThrow(failure).when(confirmations).confirm(any());
                assertSame(failure, assertThrows(RuntimeException.class, () -> directory.confirm("KL-ABC123")));
            }
            case "release" -> {
                doThrow(failure).when(releases).release(any());
                assertSame(failure, assertThrows(RuntimeException.class, () -> directory.release("KL-ABC123")));
            }
            case "markInUse" -> {
                doThrow(failure).when(departures).markInUse(any());
                assertSame(failure, assertThrows(RuntimeException.class, () -> directory.markInUse("KL-ABC123")));
            }
            case "complete" -> {
                doThrow(failure).when(completions).complete(any());
                assertSame(failure, assertThrows(RuntimeException.class, () -> directory.complete("KL-ABC123")));
            }
            case "query" -> {
                when(queries.listBusyVehicleIds(any())).thenThrow(failure);
                assertSame(failure, assertThrows(RuntimeException.class,
                        () -> directory.findBusyVehicleIds(PERIOD, Duration.ZERO, List.of(41L))));
            }
            case "move" -> {
                doThrow(failure).when(complianceMoves).moveComplianceHoldStart(any());
                assertSame(failure, assertThrows(RuntimeException.class,
                        () -> directory.moveComplianceHoldStart("KL-MOVE01", END)));
            }
            default -> throw new AssertionError("Unknown operation: " + operation);
        }
        assertExactlyOneInvocation();
    }

    /** Tập ca sai đi qua hợp đồng API thật, gồm cả kiểm tra command/query được dùng lại. */
    private static Stream<Consumer<AvailabilityDirectory>> invalidCalls() {
        return Stream.of(
                api -> api.hold(41L, null, Duration.ZERO, "booking"),
                api -> api.hold(41L, new Period(START, null), Duration.ZERO, "booking"),
                api -> api.hold(0L, PERIOD, Duration.ZERO, "booking"),
                api -> api.hold(41L, PERIOD, Duration.ofSeconds(-1), "booking"),
                api -> api.hold(41L, PERIOD, Duration.ZERO, " "),
                api -> api.block(41L, null, BlockKind.MAINTENANCE, null),
                api -> api.block(41L, PERIOD, null, null),
                api -> api.block(41L, new Period(START, null), BlockKind.MAINTENANCE, null),
                api -> api.block(41L, PERIOD, BlockKind.COMPLIANCE_HOLD, null),
                api -> api.confirm(null), api -> api.release(" "),
                api -> api.markInUse(""), api -> api.complete(null),
                api -> api.moveComplianceHoldStart(null, END),
                api -> api.moveComplianceHoldStart(" ", END),
                api -> api.moveComplianceHoldStart("KL-MOVE01", null),
                api -> api.findBusyVehicleIds(null, Duration.ZERO, List.of()),
                api -> api.findBusyVehicleIds(new Period(START, null), Duration.ZERO, List.of()),
                api -> api.findBusyVehicleIds(PERIOD, Duration.ZERO, Arrays.asList(41L, null)),
                api -> api.findBusyVehicleIds(PERIOD, Duration.ZERO, null)
        );
    }

    /** Cung cấp hai nhóm lỗi cho mỗi thao tác thay vì chỉ kiểm một cổng đại diện. */
    private static Stream<Arguments> failedCalls() {
        return Stream.of("hold", "block", "confirm", "release", "markInUse", "complete", "query", "move")
                .flatMap(operation -> Stream.of(
                        Arguments.of(operation, DomainException.conflict(ErrorCode.VEHICLE_NOT_AVAILABLE)),
                        Arguments.of(operation, new IllegalStateException("Storage unavailable."))));
    }

    /** Liệt kê dependency để bắt mọi lời gọi ngoài đúng một cổng cần ủy quyền. */
    private Object[] dependencies() {
        return new Object[]{holds, blocks, confirmations, releases, departures, completions, queries, complianceMoves};
    }

    /** Khẳng định tổng cộng đúng một tương tác, kể cả khi use case ném lỗi. */
    private void assertExactlyOneInvocation() {
        assertEquals(1, Arrays.stream(dependencies()).mapToInt(mock -> mockingDetails(mock).getInvocations().size()).sum());
    }
}
