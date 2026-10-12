package com.carrental.pricing.application.service;

import com.carrental.pricing.domain.policy.DailyRentalPricingPolicy;
import com.carrental.pricing.domain.policy.HourlyRentalPricingPolicy;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.shared.rental.RentalType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Kiểm phân giải policy tại một biên duy nhất, BR-114/235 và R6. */
class RentalPricingPolicyResolverTest {
    /** Hai gói đã hỗ trợ nhận đúng policy, không mặc định tháng thành ngày. */
    @Test
    void resolvesSupportedPackagesAndRejectsMonthly() {
        var resolver = new RentalPricingPolicyResolver();
        assertInstanceOf(DailyRentalPricingPolicy.class, resolver.resolve(RentalType.DAILY));
        assertInstanceOf(HourlyRentalPricingPolicy.class, resolver.resolve(RentalType.HOURLY));
        assertEquals(ErrorCode.QUOTE_RENTAL_TYPE_NOT_SUPPORTED,
                assertThrows(DomainException.class, () -> resolver.resolve(RentalType.MONTHLY)).errorCode());
        assertThrows(NullPointerException.class, () -> resolver.resolve(null));
    }
}
