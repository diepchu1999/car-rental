package com.carrental.vehicle.application.service;

import com.carrental.shared.rental.RentalType;
import com.carrental.vehicle.domain.OwnershipType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

/** Khóa ma trận BR-209 và quyết định không đoán chính sách PARTNER của Task 6. */
class CollateralPolicyResolverTest {
    private final CollateralPolicyResolver resolver = new CollateralPolicyResolver();

    /** Xe công ty miễn ở gói giờ/ngày, không miễn ở gói tháng. */
    @ParameterizedTest
    @CsvSource({"HOURLY,true", "DAILY,true", "MONTHLY,false"})
    void resolvesCompanyCollateral(RentalType type, boolean expected) {
        assertEquals(expected, resolver.resolve(OwnershipType.COMPANY, type).collateralFree());
    }

    /** Mọi gói PARTNER đều báo lỗi nội bộ rõ ràng, không trả cờ suy đoán. */
    @ParameterizedTest
    @EnumSource(RentalType.class)
    void rejectsUnsupportedPartnerPolicy(RentalType type) {
        var error = assertThrowsExactly(IllegalStateException.class,
                () -> resolver.resolve(OwnershipType.PARTNER, type));
        assertEquals("Collateral policy for PARTNER vehicles is not implemented.", error.getMessage());
    }

    /** Resolver không tự chọn một trục phân loại mặc định nếu lập trình viên truyền thiếu. */
    @Test
    void requiresBothAxes() {
        assertThrows(NullPointerException.class, () -> resolver.resolve(null, RentalType.DAILY));
        assertThrows(NullPointerException.class, () -> resolver.resolve(OwnershipType.COMPANY, null));
    }
}
