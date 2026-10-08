package com.carrental.booking.application.service;

import com.carrental.booking.application.view.RentalTermsDetail;
import com.carrental.booking.application.port.in.GetRentalTermsUseCase;
import com.carrental.booking.application.query.GetRentalTermsQuery;
import com.carrental.booking.domain.policy.RentalTermsPolicy;
import com.carrental.shared.validation.Validations;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.Instant;

/**
 * Phân giải và kiểm điều kiện thuê đúng một lần ở biên use case (ADR-0004, BR-125).
 * Không có SQL hoặc transaction: việc chặn lịch thuộc availability ở bước khác.
 */
@Service
class RentalTermsQueryService implements GetRentalTermsUseCase {
    private final RentalTermsPolicyResolver resolver;
    private final Clock clock;

    /** Nhận bộ phân giải nội bộ và đồng hồ chung; không dùng múi giờ mặc định của JVM. */
    RentalTermsQueryService(RentalTermsPolicyResolver resolver, Clock clock) {
        this.resolver = resolver;
        this.clock = clock;
    }

    /** Chụp hiện tại một lần, kiểm khoảng thực tế rồi trả bản giá trị bất biến cho bên gọi. */
    @Override
    public RentalTermsDetail get(GetRentalTermsQuery query) {
        Validations.required(query, "query");
        Instant at = clock.instant();
        RentalTermsPolicy policy = resolver.resolve(query.rentalType(), query.pickupMethod());
        policy.validate(query.startInclusive(), query.endExclusive(), at, clock.getZone());
        return new RentalTermsDetail(policy.turnaroundBuffer(), policy.minimumDuration(),
                policy.branchOpensAt(), policy.branchClosesAt(),
                policy.minimumAdvance(), policy.maximumAdvance());
    }
}
