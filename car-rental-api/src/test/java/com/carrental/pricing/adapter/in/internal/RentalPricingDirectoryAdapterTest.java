package com.carrental.pricing.adapter.in.internal;

import com.carrental.pricing.api.VehicleDailyRate;
import com.carrental.pricing.application.port.in.PrepareRentalPricesUseCase;
import com.carrental.pricing.application.query.PrepareRentalPricesQuery;
import com.carrental.pricing.application.view.PreparedRentalPrices;
import com.carrental.shared.rental.RentalType;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static com.carrental.pricing.domain.PricingTestFixtures.at;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Cổng BR-126 ánh xạ cả lô, không giữ giá xe hoặc gọi lại use case cho từng phần tử. */
class RentalPricingDirectoryAdapterTest {
    /** Chuẩn bị một lần rồi tính các giá khác nhau, map kết quả bất biến và không bỏ trùng mã âm thầm. */
    @Test
    void pricesWholeBatchWithoutRepeatingPreparation() {
        var useCase = mock(PrepareRentalPricesUseCase.class);
        var prepared = mock(PreparedRentalPrices.class);
        var query = new PrepareRentalPricesQuery(RentalType.DAILY, at("2026-11-13T10:00"), at("2026-11-15T10:00"));
        when(useCase.prepare(query)).thenReturn(prepared);
        when(prepared.rentalAmount(1_000_000)).thenReturn(2_200_000L);
        when(prepared.rentalAmount(855_000)).thenReturn(1_881_000L);
        var plan = new RentalPricingDirectoryAdapter(useCase).prepare(query.rentalType(), query.startAt(), query.endAt());
        var first = new VehicleDailyRate("XE-ABC123", 1_000_000);
        var second = new VehicleDailyRate("XE-DEF456", 855_000);
        var amounts = plan.rentalAmounts(List.of(first, second));
        assertEquals(Map.of(first.vehicleCode(), 2_200_000L, second.vehicleCode(), 1_881_000L), amounts);
        assertThrows(UnsupportedOperationException.class, () -> amounts.clear());
        assertTrue(plan.rentalAmounts(List.of()).isEmpty());
        verify(useCase).prepare(query);
        verifyNoMoreInteractions(useCase);
        assertThrowsExactly(IllegalArgumentException.class, () -> plan.rentalAmounts(List.of(first, first)));
    }
}
