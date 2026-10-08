package com.carrental.booking.adapter.in.internal;

import com.carrental.booking.api.RentalTerms;
import com.carrental.booking.api.RentalTermsDirectory;
import com.carrental.booking.application.port.in.GetRentalTermsUseCase;
import com.carrental.booking.application.query.GetRentalTermsQuery;
import com.carrental.booking.application.view.RentalTermsDetail;
import com.carrental.shared.rental.PickupMethod;
import com.carrental.shared.rental.RentalType;
import org.springframework.stereotype.Component;
import java.time.Instant;

/** Nối cổng cross-module với use case theo ADR-0004/0008; không chứa quy tắc thuê. */
@Component
class RentalTermsDirectoryAdapter implements RentalTermsDirectory {
    private final GetRentalTermsUseCase terms;

    /** Chỉ nhận cổng đầu vào, không phụ thuộc implementation của application. */
    RentalTermsDirectoryAdapter(GetRentalTermsUseCase terms) {
        this.terms = terms;
    }

    /** Giữ nguyên thời điểm thực tế, gói thuê và cách nhận; không tự cộng đệm hoặc đọc đồng hồ. */
    @Override
    public RentalTerms getTerms(RentalType rentalType, PickupMethod pickupMethod,
                                Instant startInclusive, Instant endExclusive) {
        RentalTermsDetail detail = terms.get(GetRentalTermsQuery.from(
                rentalType, pickupMethod, startInclusive, endExclusive));
        return new RentalTerms(detail.turnaroundBuffer(), detail.minimumDuration(),
                detail.branchOpensAt(), detail.branchClosesAt(),
                detail.minimumAdvance(), detail.maximumAdvance());
    }
}
