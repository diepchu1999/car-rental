package com.carrental.pricing.application.service;

import com.carrental.pricing.application.port.out.ReadPricingPolicyPort;
import com.carrental.pricing.application.query.PrepareRentalPricesQuery;
import com.carrental.pricing.domain.*;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.shared.rental.RentalType;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import static com.carrental.pricing.domain.PricingTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** BR-126/218: một kế hoạch dùng cho nhiều xe, cùng domain với báo giá, không truy vấn từng xe. */
class RentalPricingQueryServiceTest {
    /** Hai mốc phân loại đúng một lần; gọi tính cho một trăm xe không đọc cấu hình hay phân loại lại. */
    @Test
    void preparesDaysAndReadsConfigurationOnceForWholeBatch() {
        var settings = spy(parameters());
        var source = mock(ReadPricingPolicyPort.class);
        var now = at("2026-10-12T09:00");
        when(source.parametersAt(now)).thenReturn(settings);
        var service = new RentalPricingQueryService(new RentalPricingPolicyResolver(), source, Clock.fixed(now, ZONE));
        var start = at("2026-11-13T10:00");
        var plan = service.prepare(new PrepareRentalPricesQuery(RentalType.DAILY, start, at("2026-11-15T10:00")));
        verify(settings).dayAt(start, ZONE);
        verify(settings).dayAt(start.plus(Duration.ofHours(24)), ZONE);
        for (int index = 0; index < 100; index++) assertEquals(2_200_000, plan.rentalAmount(1_000_000));
        assertEquals(1_881_000, plan.rentalAmount(855_000));
        verify(source, times(1)).parametersAt(now);
        verifyNoMoreInteractions(source);
        verify(settings, times(2)).dayAt(any(), any());
    }

    /** BR-218: không cần có xe mới phát hiện khoảng vượt ngày khai báo. */
    @Test
    void failsCalendarDuringPrepareRatherThanWhenPricingFirstVehicle() {
        var source = mock(ReadPricingPolicyPort.class);
        when(source.parametersAt(any())).thenReturn(parameters());
        var service = new RentalPricingQueryService(new RentalPricingPolicyResolver(), source,
                Clock.fixed(at("2026-10-12T09:00"), ZONE));
        var error = assertThrowsExactly(DomainException.class, () -> service.prepare(
                new PrepareRentalPricesQuery(RentalType.DAILY, at("2027-03-01T10:00"), at("2028-01-02T10:00"))));
        assertEquals(ErrorCode.HOLIDAY_CALENDAR_NOT_DECLARED, error.errorCode());
    }

    /** Search không tính phí bảo hiểm, kể cả cấu hình phí lớn làm tổng báo giá tràn BIGINT. */
    @Test
    void rentalCalculationDoesNotEvaluateInsurance() {
        var base = parameters();
        var settings = new PricingParameters(base.weekendDays(), base.weekendUplift(), base.holidayUplift(),
                base.holidayCalendar(), new InsuranceTerms(InsuranceCalculation.PERCENT_OF_RENT,
                        new BigDecimal("10000000000000000000000000000000000")), base.quoteValidity());
        var source = mock(ReadPricingPolicyPort.class);
        when(source.parametersAt(any())).thenReturn(settings);
        var service = new RentalPricingQueryService(new RentalPricingPolicyResolver(), source,
                Clock.fixed(at("2026-10-12T09:00"), ZONE));
        var plan = service.prepare(new PrepareRentalPricesQuery(RentalType.HOURLY,
                at("2026-11-10T08:00"), at("2026-11-10T15:00")));
        assertEquals(685_000, plan.rentalAmount(855_000));
    }
}
