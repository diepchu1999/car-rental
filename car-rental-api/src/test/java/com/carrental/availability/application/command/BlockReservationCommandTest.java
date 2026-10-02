package com.carrental.availability.application.command;

import com.carrental.availability.domain.ReservationKind;
import com.carrental.availability.domain.ReservationPeriod;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** Kiểm đầu vào khóa vận hành, chỉ COMPLIANCE_HOLD được không chặn trên theo BR-015. */
class BlockReservationCommandTest {

    private static final Instant START = Instant.parse("2030-10-01T03:00:00Z");
    private static final Instant END = START.plusSeconds(7200);

    /** Kiểm cả năm loại vận hành nhận khoảng hữu hạn qua factory và constructor. */
    @ParameterizedTest
    @EnumSource(value = ReservationKind.class, names = "RENTAL", mode = EnumSource.Mode.EXCLUDE)
    void acceptsFiniteOperationalKinds(ReservationKind kind) {
        BlockReservationCommand command = BlockReservationCommand.from(42L, START, END, kind, " Workshop ");
        assertEquals(new BlockReservationCommand(42L, ReservationPeriod.finite(START, END), kind, " Workshop "), command);
        assertEquals(42L, command.vehicleId());
        assertEquals(START, command.period().startInclusive());
        assertEquals(END, command.period().endExclusive());
        assertEquals(kind, command.kind());
    }

    /** Kiểm compliance biểu diễn cận trên null thật, không dùng một ngày giả. */
    @Test
    void acceptsUnboundedCompliancePeriod() {
        BlockReservationCommand command = BlockReservationCommand.from(42L, START, null,
                ReservationKind.COMPLIANCE_HOLD, null);
        assertTrue(command.period().isUnbounded());
        assertEquals(START, command.period().startInclusive());
        assertNull(command.period().endExclusive());
    }

    /** Kiểm mọi loại khác compliance đều bị chặn khi nhận khoảng vô hạn, qua cả hai lối khởi tạo. */
    @ParameterizedTest
    @EnumSource(value = ReservationKind.class, names = "COMPLIANCE_HOLD", mode = EnumSource.Mode.EXCLUDE)
    void rejectsUnboundedPeriodForOtherKinds(ReservationKind kind) {
        assertInvalid(() -> BlockReservationCommand.from(42L, START, null, kind, null));
        assertInvalid(() -> new BlockReservationCommand(42L, ReservationPeriod.unboundedFrom(START), kind, null));
    }

    /** Kiểm factory từ chối xe thiếu/không dương; constructor cũng không bỏ qua kiểm số dương. */
    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void rejectsInvalidVehicleId(Long vehicleId) {
        assertInvalid(() -> BlockReservationCommand.from(vehicleId, START, END, ReservationKind.MAINTENANCE, null));
        if (vehicleId != null) {
            assertInvalid(() -> new BlockReservationCommand(vehicleId,
                    ReservationPeriod.finite(START, END), ReservationKind.MAINTENANCE, null));
        }
    }

    /** Kiểm thiếu khoảng/loại, sai đầu mút và RENTAL đều không được lọt qua command. */
    @Test
    void rejectsMissingFieldsInvalidBoundsAndRental() {
        ReservationPeriod period = ReservationPeriod.finite(START, END);
        assertInvalid(() -> new BlockReservationCommand(42L, null, ReservationKind.MAINTENANCE, null));
        assertInvalid(() -> new BlockReservationCommand(42L, period, null, null));
        assertInvalid(() -> new BlockReservationCommand(42L, period, ReservationKind.RENTAL, null));
        assertInvalid(() -> BlockReservationCommand.from(42L, START, END, null, null));
        assertInvalid(() -> BlockReservationCommand.from(42L, START, END, ReservationKind.RENTAL, null));
        assertInvalid(() -> BlockReservationCommand.from(42L, null, END, ReservationKind.MAINTENANCE, null));
        assertInvalid(() -> BlockReservationCommand.from(42L, START, START, ReservationKind.MAINTENANCE, null));
        assertInvalid(() -> BlockReservationCommand.from(42L, END, START, ReservationKind.MAINTENANCE, null));
    }

    /** Kiểm reason không bị tự thêm ràng buộc bắt buộc hay cắt khoảng trắng ngoài hợp đồng DDL. */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "  Workshop inspection  "})
    void preservesOptionalReason(String reason) {
        assertEquals(reason, BlockReservationCommand.from(42L, START, END,
                ReservationKind.INSPECTION, reason).reason());
    }

    /** Chỉ chấp nhận lỗi đầu vào có đúng mã và nhóm, không coi exception bất kỳ là đạt. */
    private static void assertInvalid(Runnable operation) {
        DomainException failure = assertThrows(DomainException.class, operation::run);
        assertEquals(ErrorCode.INVALID_REQUEST, failure.errorCode());
        assertEquals(DomainException.Category.INVALID_INPUT, failure.category());
    }
}
