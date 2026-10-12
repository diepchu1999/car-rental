package com.carrental.pricing.application.service;

import com.carrental.booking.api.RentalTermsDirectory;
import com.carrental.pricing.application.command.CreateQuoteCommand;
import com.carrental.pricing.application.port.out.*;
import com.carrental.pricing.domain.Quote;
import com.carrental.shared.code.BusinessCodeGenerator;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.shared.rental.*;
import com.carrental.vehicle.api.VehiclePricingDirectory;
import com.carrental.vehicle.api.VehiclePricingRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;
import static com.carrental.pricing.domain.PricingTestFixtures.*;
import static com.carrental.pricing.domain.QuoteTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Khóa thứ tự kiểm lỗi, giới hạn thử mã và snapshot giữ nguyên qua thử lại; rollback được kiểm bằng integration. */
class QuoteCommandServiceTest {
    private RentalTermsDirectory terms;
    private ReadPricingPolicyPort settings;
    private VehiclePricingDirectory vehicles;
    private WriteQuotePort writes;
    private ReadQuotePort reads;
    private BusinessCodeGenerator codes;
    private QuoteCommandService service;

    /** Tạo mock mới; các số bảo hiểm chỉ là fixture test. */
    @BeforeEach
    void setUp() {
        terms = mock(RentalTermsDirectory.class); settings = mock(ReadPricingPolicyPort.class);
        vehicles = mock(VehiclePricingDirectory.class); writes = mock(WriteQuotePort.class);
        reads = mock(ReadQuotePort.class); codes = mock(BusinessCodeGenerator.class);
        when(settings.parametersAt(CREATED_AT)).thenReturn(parameters());
        when(vehicles.findActiveByCode("XE-TEST01")).thenReturn(Optional.of(new VehiclePricingRef("XE-TEST01", 1_000_000)));
        when(codes.generate("BG")).thenReturn("BG-AAAAAA", "BG-BBBBBB", "BG-CCCCCC", "BG-DDDDDD", "BG-EEEEEE");
        service = new QuoteCommandService(new RentalPricingPolicyResolver(), terms, settings, vehicles,
                writes, reads, codes, Clock.fixed(CREATED_AT, ZONE));
    }

    /** Trùng mã chỉ thử mã mới, đọc policy/xe một lần và giữ nguyên hạn/giá qua mọi lượt. */
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 4})
    void retriesOnlyCodeAndUsesVietnamMonth(int collisions) {
        var saved = new ArrayList<Quote>();
        when(writes.insert(any())).thenAnswer(call -> { saved.add(call.getArgument(0)); return saved.size() > collisions; });
        when(reads.findByCode(anyString())).thenAnswer(call -> Optional.of(detail(saved.getLast())));
        var result = service.create(command());
        assertEquals(collisions + 1, saved.size());
        assertTrue(result.code().startsWith("BG-2611-")); // UTC còn tháng 10, Việt Nam đã tháng 11.
        assertEquals(2_200_000, result.price().rentalAmount());
        for (var quote : saved) {
            assertEquals(saved.getFirst().snapshot(), quote.snapshot());
            assertEquals(saved.getFirst().price(), quote.price());
            assertEquals(saved.getFirst().expiresAt(), quote.expiresAt());
            assertEquals(saved.getFirst().createdAt(), quote.createdAt());
        }
        verify(terms).getTerms(RentalType.DAILY, PickupMethod.BRANCH, command().startAt(), command().endAt());
        verify(settings).parametersAt(CREATED_AT); verify(vehicles).findActiveByCode("XE-TEST01");
        verify(reads).findByCode(result.code()); verify(codes, times(collisions + 1)).generate("BG");
        verifyNoMoreInteractions(terms, settings, vehicles, reads, codes);
    }

    /** Hết năm lượt trùng mã trả lỗi nội bộ, không đọc lại hoặc tự ghi đè báo giá cũ. */
    @Test
    void codeRetriesHaveHardLimit() {
        assertThrows(IllegalStateException.class, () -> service.create(command()));
        verify(writes, times(5)).insert(any()); verify(codes, times(5)).generate("BG");
        verifyNoInteractions(reads);
    }

    /** Chặn lựa chọn chưa hỗ trợ trước điều kiện thuê, lịch lễ và tra xe. */
    @Test
    void unsupportedChoicesHaveDeterministicPrecedence() {
        var base = command();
        assertEquals(ErrorCode.QUOTE_RENTAL_TYPE_NOT_SUPPORTED, assertThrows(DomainException.class,
                () -> service.create(new CreateQuoteCommand(base.vehicleCode(), base.startAt(), base.endAt(),
                        RentalType.MONTHLY, DriveMode.WITH_DRIVER, PickupMethod.DELIVERY))).errorCode());
        assertEquals(ErrorCode.QUOTE_DRIVE_MODE_NOT_SUPPORTED, assertThrows(DomainException.class,
                () -> service.create(new CreateQuoteCommand(base.vehicleCode(), base.startAt(), base.endAt(),
                        RentalType.DAILY, DriveMode.WITH_DRIVER, PickupMethod.DELIVERY))).errorCode());
        assertEquals(ErrorCode.QUOTE_PICKUP_METHOD_NOT_SUPPORTED, assertThrows(DomainException.class,
                () -> service.create(new CreateQuoteCommand(base.vehicleCode(), base.startAt(), base.endAt(),
                        RentalType.DAILY, DriveMode.SELF_DRIVE, PickupMethod.DELIVERY))).errorCode());
        verifyNoInteractions(terms, settings, vehicles, writes, reads, codes);
    }

    /** Điều kiện thuê sai đứng trước hạn lịch lễ và không ghi dữ liệu. */
    @Test
    void rentalTermsFailureStopsBeforeCalendarAndVehicle() {
        when(terms.getTerms(any(), any(), any(), any()))
                .thenThrow(DomainException.ruleViolation(ErrorCode.OUTSIDE_BRANCH_HOURS));
        assertEquals(ErrorCode.OUTSIDE_BRANCH_HOURS,
                assertThrows(DomainException.class, () -> service.create(command())).errorCode());
        verifyNoInteractions(settings, vehicles, writes, reads, codes);
    }

    /** Vượt lịch lễ bị từ chối trước tra xe, kể cả mã xe cũng không tồn tại. */
    @Test
    void calendarFailurePrecedesMissingVehicle() {
        var command = new CreateQuoteCommand("XE-MISS01", at("2027-03-01T10:00"), at("2028-01-02T10:00"),
                RentalType.DAILY, DriveMode.SELF_DRIVE, PickupMethod.BRANCH);
        assertEquals(ErrorCode.HOLIDAY_CALENDAR_NOT_DECLARED,
                assertThrows(DomainException.class, () -> service.create(command)).errorCode());
        verifyNoInteractions(vehicles, writes, reads, codes);
    }

    /** Xe thiếu hoặc chưa ACTIVE không có báo giá; cổng trả rỗng chứ không lộ trạng thái hồ sơ. */
    @Test
    void missingVehicleNeverWritesQuote() {
        when(vehicles.findActiveByCode(anyString())).thenReturn(Optional.empty());
        assertEquals(ErrorCode.VEHICLE_NOT_FOUND,
                assertThrows(DomainException.class, () -> service.create(command())).errorCode());
        verifyNoInteractions(writes, reads, codes);
    }

    /** Lỗi INSERT khác không được coi là trùng mã hoặc thử lại. */
    @Test
    void writeFailureIsNotRetried() {
        var expected = new IllegalStateException("Simulated database failure.");
        when(writes.insert(any())).thenThrow(expected);
        assertSame(expected, assertThrows(IllegalStateException.class, () -> service.create(command())));
        verify(writes).insert(any()); verify(codes).generate("BG"); verifyNoInteractions(reads);
    }

    /** Chèn thành công nhưng reload thiếu phải báo lỗi, không trả object chưa được lưu kiểm chứng. */
    @Test
    void missingReloadIsInternalFailure() {
        when(writes.insert(any())).thenReturn(true);
        assertThrows(IllegalStateException.class, () -> service.create(command()));
        verify(writes).insert(any()); verify(reads).findByCode("BG-2611-AAAAAA");
    }

    /** Khoảng ví dụ a, không dùng availability hoặc tự kiểm xe còn trống. */
    private static CreateQuoteCommand command() {
        return new CreateQuoteCommand("XE-TEST01", at("2026-11-13T10:00"), at("2026-11-15T10:00"),
                RentalType.DAILY, DriveMode.SELF_DRIVE, PickupMethod.BRANCH);
    }
}
