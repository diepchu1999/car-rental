package com.carrental.availability.application.command;

import com.carrental.availability.domain.ReservationPeriod;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Kiểm hợp đồng đầu vào giữ chỗ, không thêm quy tắc đặt thuê thuộc module booking. */
class HoldReservationCommandTest {

    private static final Instant START = Instant.parse("2030-10-01T03:00:00Z");
    private static final Instant END = START.plusSeconds(7200);

    /**
     * Kiểm command giữ khoảng chưa cộng đệm và không tự chọn độ dài theo gói thuê.
     *
     * @param hours đệm do bên gọi cung cấp
     */
    @ParameterizedTest
    @ValueSource(longs = {0, 1, 2})
    void preservesUnbufferedInput(long hours) {
        HoldReservationCommand command = HoldReservationCommand.from(
                42L, START, END, Duration.ofHours(hours), " booking-code "
        );
        assertEquals(42L, command.vehicleId());
        assertEquals(ReservationPeriod.finite(START, END), command.rentalPeriod());
        assertEquals(Duration.ofHours(hours), command.buffer());
        assertEquals(" booking-code ", command.bookingCode());
    }

    /** Kiểm khoảng quá khứ không bị tự cấm vì điều kiện đặt thuê thuộc booking. */
    @Test
    void doesNotAddBookingWindowRules() {
        Instant past = Instant.parse("2000-01-01T00:00:00Z");
        assertDoesNotThrow(() -> HoldReservationCommand.from(42L, past, past.plusSeconds(3600),
                Duration.ZERO, "booking-code"));
    }

    /**
     * Kiểm cả factory và constructor trực tiếp đều từ chối dữ liệu thiếu hoặc sai cấu trúc.
     *
     * @param action cách tạo command không hợp lệ
     */
    @ParameterizedTest
    @MethodSource("invalidCommands")
    void rejectsInvalidInput(Executable action) {
        DomainException failure = assertThrows(DomainException.class, action);
        assertEquals(ErrorCode.INVALID_REQUEST, failure.errorCode());
        assertEquals(DomainException.Category.INVALID_INPUT, failure.category());
    }

    /** Cung cấp các đầu vào vi phạm hợp đồng giữ chỗ để không phụ thuộc dữ liệu thật. */
    private static Stream<Executable> invalidCommands() {
        ReservationPeriod finite = ReservationPeriod.finite(START, END);
        return Stream.of(
                () -> HoldReservationCommand.from(null, START, END, Duration.ZERO, "booking"),
                () -> HoldReservationCommand.from(0L, START, END, Duration.ZERO, "booking"),
                () -> HoldReservationCommand.from(-1L, START, END, Duration.ZERO, "booking"),
                () -> HoldReservationCommand.from(42L, null, END, Duration.ZERO, "booking"),
                () -> HoldReservationCommand.from(42L, START, null, Duration.ZERO, "booking"),
                () -> HoldReservationCommand.from(42L, START, START, Duration.ZERO, "booking"),
                () -> HoldReservationCommand.from(42L, END, START, Duration.ZERO, "booking"),
                () -> new HoldReservationCommand(42L, null, Duration.ZERO, "booking"),
                () -> new HoldReservationCommand(42L, ReservationPeriod.unboundedFrom(START), Duration.ZERO, "booking"),
                () -> new HoldReservationCommand(42L, finite, null, "booking"),
                () -> new HoldReservationCommand(42L, finite, Duration.ofSeconds(-1), "booking"),
                () -> new HoldReservationCommand(42L, finite, Duration.ZERO, null),
                () -> new HoldReservationCommand(42L, finite, Duration.ZERO, ""),
                () -> new HoldReservationCommand(42L, finite, Duration.ZERO, "  ")
        );
    }
}
