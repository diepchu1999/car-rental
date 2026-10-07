package com.carrental.search.application.service;

import com.carrental.search.domain.SearchSort;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.shared.rental.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

/** Chứng minh hỗ trợ/từ chối rõ ràng theo BR-125/126 mà không rải nhánh RentalType ngoài resolver. */
class SearchSupportPolicyResolverTest {
    private final SearchSupportPolicyResolver resolver = new SearchSupportPolicyResolver();

    /** Gói giờ và ngày tự lái tại chi nhánh đều được phép. */
    @ParameterizedTest
    @EnumSource(value = RentalType.class, names = {"HOURLY", "DAILY"})
    void permitsSupportedSlice(RentalType rentalType) {
        assertDoesNotThrow(() -> resolver.resolve(rentalType, DriveMode.SELF_DRIVE,
                PickupMethod.BRANCH, SearchSort.NEAREST).validate());
    }

    /** Mỗi lựa chọn chưa hỗ trợ có mã lỗi riêng, không thành kết quả rỗng. */
    @ParameterizedTest
    @CsvSource({
            "MONTHLY,SELF_DRIVE,BRANCH,NEAREST,SEARCH_RENTAL_TYPE_NOT_SUPPORTED",
            "DAILY,WITH_DRIVER,BRANCH,NEAREST,SEARCH_DRIVE_MODE_NOT_SUPPORTED",
            "DAILY,SELF_DRIVE,DELIVERY,NEAREST,SEARCH_PICKUP_METHOD_NOT_SUPPORTED",
            "DAILY,SELF_DRIVE,BRANCH,PRICE_ASC,SEARCH_SORT_NOT_SUPPORTED",
            "DAILY,SELF_DRIVE,BRANCH,RATING_DESC,SEARCH_SORT_NOT_SUPPORTED"
    })
    void rejectsUnsupportedOptions(RentalType rentalType, DriveMode driveMode,
            PickupMethod pickupMethod, SearchSort sort, ErrorCode code) {
        var policy = resolver.resolve(rentalType, driveMode, pickupMethod, sort);
        var error = assertThrowsExactly(DomainException.class, policy::validate);
        assertEquals(code, error.errorCode());
        assertEquals(DomainException.Category.RULE_VIOLATION, error.category());
    }
}
