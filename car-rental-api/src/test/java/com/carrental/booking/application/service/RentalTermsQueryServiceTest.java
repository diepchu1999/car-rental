package com.carrental.booking.application.service;

import com.carrental.booking.application.view.RentalTermsDetail;
import com.carrental.booking.application.query.GetRentalTermsQuery;
import com.carrental.booking.domain.policy.RentalTermsPolicy;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.shared.rental.PickupMethod;
import com.carrental.shared.rental.RentalType;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.Period;
import java.time.ZoneId;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

/** Kiểm điều phối: một lần phân giải, một lần đọc Clock, giữ nguyên khoảng thuê. */
class RentalTermsQueryServiceTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Instant AT = Instant.parse("2026-10-05T01:00:00Z");

    /** ADR-0004: một lần resolve và một mốc hiện tại; đầu ra đúng policy và không cộng đệm. */
    @Test
    void resolvesOnceAndReadsClockOnce() {
        CountingResolver resolver = new CountingResolver();
        CountingClock clock = new CountingClock();
        RentalTermsQueryService service = new RentalTermsQueryService(resolver, clock);
        Instant start = Instant.parse("2026-10-06T00:00:00Z");
        Instant end = start.plus(Duration.ofHours(4));
        GetRentalTermsQuery query = GetRentalTermsQuery.from(RentalType.HOURLY, PickupMethod.BRANCH, start, end);
        RentalTermsDetail actual = service.get(query);
        assertEquals(new RentalTermsDetail(Duration.ofHours(1), Optional.of(Duration.ofHours(4)),
                LocalTime.of(6, 0), LocalTime.of(23, 0), Duration.ofHours(1), Period.ofMonths(6)), actual);
        assertEquals(1, resolver.calls);
        assertEquals(1, clock.reads);
        assertEquals(start, query.startInclusive());
        assertEquals(end, query.endExclusive());
    }

    /** Không trả điều kiện thành công khi khoảng thuê vi phạm tối thiểu. */
    @Test
    void propagatesPolicyViolation() {
        RentalTermsQueryService service = new RentalTermsQueryService(new RentalTermsPolicyResolver(), Clock.fixed(AT, ZONE));
        Instant start = Instant.parse("2026-10-05T03:00:00Z");
        DomainException failure = assertThrowsExactly(DomainException.class,
                () -> service.get(GetRentalTermsQuery.from(RentalType.HOURLY, PickupMethod.BRANCH, start, start.plusSeconds(10_800))));
        assertEquals(ErrorCode.RENTAL_DURATION_TOO_SHORT, failure.errorCode());
        assertEquals(DomainException.Category.RULE_VIOLATION, failure.category());
    }

    /** Query null bị chặn trước khi đọc Clock hoặc phân giải policy. */
    @Test
    void rejectsNullQueryBeforeReadingDependencies() {
        CountingResolver resolver = new CountingResolver();
        CountingClock clock = new CountingClock();
        DomainException failure = assertThrowsExactly(DomainException.class,
                () -> new RentalTermsQueryService(resolver, clock).get(null));
        assertEquals(ErrorCode.INVALID_REQUEST, failure.errorCode());
        assertEquals(0, resolver.calls);
        assertEquals(0, clock.reads);
    }

    /** Probe đếm số lần phân giải nhưng vẫn dùng policy thật. */
    private static class CountingResolver extends RentalTermsPolicyResolver {
        private int calls;

        /** Đếm lần gọi rồi ủy quyền cho resolver thật. */
        @Override
        RentalTermsPolicy resolve(RentalType type, PickupMethod pickup) {
            calls++;
            return super.resolve(type, pickup);
        }
    }

    /** Đồng hồ có kiểm đếm để bắt việc đọc hiện tại nhiều lần trong cùng use case. */
    private static class CountingClock extends Clock {
        private int reads;

        /** Trả múi giờ nghiệp vụ, khác UTC để bắt lỗi dùng sai zone. */
        @Override
        public ZoneId getZone() { return ZONE; }

        /** Tạo đồng hồ cố định theo zone khác nếu bên gọi yêu cầu. */
        @Override
        public Clock withZone(ZoneId zone) { return Clock.fixed(AT, zone); }

        /** Đếm lần đọc và trả cùng mốc fixture. */
        @Override
        public Instant instant() { reads++; return AT; }
    }
}
