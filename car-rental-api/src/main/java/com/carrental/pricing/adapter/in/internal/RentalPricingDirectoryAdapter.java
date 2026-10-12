package com.carrental.pricing.adapter.in.internal;

import com.carrental.pricing.api.RentalPricePlan;
import com.carrental.pricing.api.RentalPricingDirectory;
import com.carrental.pricing.application.port.in.PrepareRentalPricesUseCase;
import com.carrental.pricing.application.query.PrepareRentalPricesQuery;
import com.carrental.shared.rental.RentalType;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** Biên cross-module chỉ qua pricing.api; không để search nhận policy/domain nội bộ. */
@Component
class RentalPricingDirectoryAdapter implements RentalPricingDirectory {
    private final PrepareRentalPricesUseCase useCase;

    /** Nhận port/in thay vì gọi thẳng service hoặc adapter cấu hình. */
    RentalPricingDirectoryAdapter(PrepareRentalPricesUseCase useCase) {
        this.useCase = useCase;
    }

    /** Chuẩn bị trước tìm ứng viên rồi tính cả lô, mã trùng là lỗi hợp đồng nội bộ. */
    @Override
    public RentalPricePlan prepare(RentalType rentalType, Instant startAt, Instant endAt) {
        var prepared = useCase.prepare(new PrepareRentalPricesQuery(rentalType, startAt, endAt));
        return vehicles -> {
            var amounts = new HashMap<String, Long>();
            for (var vehicle : vehicles) {
                if (amounts.putIfAbsent(vehicle.vehicleCode(), prepared.rentalAmount(vehicle.dailyRate())) != null) {
                    throw new IllegalArgumentException("Duplicate vehicle code in rental pricing batch.");
                }
            }
            return Map.copyOf(amounts);
        };
    }
}
