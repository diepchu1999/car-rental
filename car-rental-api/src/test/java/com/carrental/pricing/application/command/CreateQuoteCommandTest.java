package com.carrental.pricing.application.command;

import com.carrental.pricing.application.query.GetQuoteQuery;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.shared.rental.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

/** Validation cấu trúc command/query, không truy vấn CSDL hoặc tự đặt quy tắc thuê mới. */
class CreateQuoteCommandTest {
    private static final Instant START = Instant.parse("2026-11-10T03:00:00Z");
    private static final Instant END = START.plusSeconds(21_600);

    /** Giữ nguyên đầu vào, kể cả mã có khoảng trắng; enum chưa hỗ trợ để resolver từ chối rõ ràng. */
    @Test
    void preservesValuesAndDefersSupportValidationToResolver() {
        var command = CreateQuoteCommand.from(" XE-TEST01 ", START, END,
                RentalType.MONTHLY, DriveMode.WITH_DRIVER, PickupMethod.DELIVERY);
        assertEquals(" XE-TEST01 ", command.vehicleCode());
        assertEquals(START, command.startAt());
        assertEquals(END, command.endAt());
        assertEquals(RentalType.MONTHLY, command.rentalType());
        assertEquals(" BG-2611-TEST01 ", GetQuoteQuery.from(" BG-2611-TEST01 ").code());
    }

    /** Mã thiếu/trắng bị chặn ở cả constructor và factory, trước mọi gọi module. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void rejectsMissingCodes(String code) {
        assertEquals(ErrorCode.INVALID_REQUEST, assertThrows(DomainException.class,
                () -> CreateQuoteCommand.from(code, START, END, RentalType.DAILY,
                        DriveMode.SELF_DRIVE, PickupMethod.BRANCH)).errorCode());
        assertEquals(ErrorCode.INVALID_REQUEST,
                assertThrows(DomainException.class, () -> new GetQuoteQuery(code)).errorCode());
    }

    /** Thiếu thời điểm/enum hoặc khoảng không dương đều là INVALID_REQUEST, không sinh báo giá. */
    @Test
    void rejectsMissingFieldsAndNonpositivePeriod() {
        assertThrows(DomainException.class, () -> new CreateQuoteCommand("XE-TEST01", null, END,
                RentalType.DAILY, DriveMode.SELF_DRIVE, PickupMethod.BRANCH));
        assertThrows(DomainException.class, () -> new CreateQuoteCommand("XE-TEST01", START, null,
                RentalType.DAILY, DriveMode.SELF_DRIVE, PickupMethod.BRANCH));
        assertThrows(DomainException.class, () -> new CreateQuoteCommand("XE-TEST01", START, END,
                null, DriveMode.SELF_DRIVE, PickupMethod.BRANCH));
        assertThrows(DomainException.class, () -> new CreateQuoteCommand("XE-TEST01", START, END,
                RentalType.DAILY, null, PickupMethod.BRANCH));
        assertThrows(DomainException.class, () -> new CreateQuoteCommand("XE-TEST01", START, END,
                RentalType.DAILY, DriveMode.SELF_DRIVE, null));
        for (var end : new Instant[]{START, START.minusNanos(1)}) {
            assertEquals(ErrorCode.INVALID_REQUEST, assertThrows(DomainException.class,
                    () -> new CreateQuoteCommand("XE-TEST01", START, end, RentalType.DAILY,
                            DriveMode.SELF_DRIVE, PickupMethod.BRANCH)).errorCode());
        }
    }

    /** Từ chối phần dưới micro của đầu vào để PostgreSQL không làm đổi ranh giới bốn giờ tính giá. */
    @Test
    void rejectsUnrepresentableTimestampPrecisionWithoutRounding() {
        assertEquals(ErrorCode.INVALID_REQUEST, assertThrows(DomainException.class,
                () -> new CreateQuoteCommand("XE-TEST01", START.plusNanos(1), END.plusNanos(1),
                        RentalType.HOURLY, DriveMode.SELF_DRIVE, PickupMethod.BRANCH)).errorCode());
        var representable = new CreateQuoteCommand("XE-TEST01", START.plusNanos(1_000), END.plusNanos(1_000),
                RentalType.HOURLY, DriveMode.SELF_DRIVE, PickupMethod.BRANCH);
        assertEquals(START.plusNanos(1_000), representable.startAt());
    }
}
