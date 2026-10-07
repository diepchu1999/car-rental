package com.carrental.booking.application.service;

import com.carrental.booking.domain.policy.RentalTermsPolicy;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.shared.rental.PickupMethod;
import com.carrental.shared.rental.RentalType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.time.Duration;
import java.time.LocalTime;
import java.time.Period;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

/** Kiểm đủ ma trận 3 gói thuê và 2 cách nhận theo BR-109/113/116/119/121. */
class RentalTermsPolicyResolverTest {
    private final RentalTermsPolicyResolver resolver = new RentalTermsPolicyResolver();

    /** Mỗi tổ hợp chọn đúng đệm, tối thiểu và cửa sổ; không mặc định âm thầm sang gói khác. */
    @ParameterizedTest
    @CsvSource({
            "HOURLY,BRANCH,1,4,1", "HOURLY,DELIVERY,1,4,3",
            "DAILY,BRANCH,2,0,1", "DAILY,DELIVERY,2,0,3",
            "MONTHLY,BRANCH,2,0,1", "MONTHLY,DELIVERY,2,0,3"
    })
    void resolvesAllTerms(RentalType type, PickupMethod pickup, int buffer, int minimum, int advance) {
        RentalTermsPolicy policy = resolver.resolve(type, pickup);
        assertEquals(Duration.ofHours(buffer), policy.turnaroundBuffer());
        assertEquals(minimum == 0 ? Optional.empty() : Optional.of(Duration.ofHours(minimum)), policy.minimumDuration());
        assertEquals(LocalTime.of(6, 0), policy.branchOpensAt());
        assertEquals(LocalTime.of(23, 0), policy.branchClosesAt());
        assertEquals(Duration.ofHours(advance), policy.minimumAdvance());
        assertEquals(Period.ofMonths(6), policy.maximumAdvance());
    }

    /** Resolver cũng báo thiếu rõ ràng nếu được gọi ngoài đường query đã kiểm. */
    @Test
    void rejectsMissingClassification() {
        DomainException typeFailure = assertThrowsExactly(DomainException.class,
                () -> resolver.resolve(null, PickupMethod.BRANCH));
        DomainException pickupFailure = assertThrowsExactly(DomainException.class,
                () -> resolver.resolve(RentalType.DAILY, null));
        assertEquals(ErrorCode.INVALID_REQUEST, typeFailure.errorCode());
        assertEquals("rentalType is required.", typeFailure.getMessage());
        assertEquals(ErrorCode.INVALID_REQUEST, pickupFailure.errorCode());
        assertEquals("pickupMethod is required.", pickupFailure.getMessage());
    }
}
