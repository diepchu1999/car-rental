package com.carrental.booking.application.service;

import com.carrental.availability.api.AvailabilityDirectory;
import com.carrental.booking.application.command.ExpireBookingHoldCommand;
import com.carrental.booking.application.port.in.ExpireBookingHoldUseCase;
import com.carrental.booking.application.port.in.SweepExpiredBookingHoldsUseCase;
import com.carrental.booking.application.port.out.ReadBookingPort;
import com.carrental.shared.logging.FailureSummary;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** BR-103/304: lỗi một dòng không ngăn các dòng khác hoặc lần dọn kế tiếp. */
@Service
class BookingHoldSweepService implements SweepExpiredBookingHoldsUseCase {
    private static final Logger LOG = LoggerFactory.getLogger(BookingHoldSweepService.class);
    private final ReadBookingPort reads;
    private final AvailabilityDirectory availability;
    private final ExpireBookingHoldUseCase expiry;
    private final Clock clock;

    /** Nhận use case của từng dòng qua bean riêng, không tự gọi method trong cùng class. */
    BookingHoldSweepService(ReadBookingPort reads, AvailabilityDirectory availability, ExpireBookingHoldUseCase expiry, Clock clock) {
        this.reads = reads;
        this.availability = availability;
        this.expiry = expiry;
        this.clock = clock;
    }

    /** Không có giao dịch cả đợt; hai nguồn chỉ đọc ứng viên, câu ghi từng dòng kiểm lại điều kiện. */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int sweep() {
        var at = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var candidates = new LinkedHashSet<ExpireBookingHoldCommand>();
        reads.findExpiredPending(at).forEach(ref -> candidates.add(new ExpireBookingHoldCommand(ref.bookingCode(), ref.reservationCode(), at)));
        availability.findExpiredHolds(at).forEach(ref -> candidates.add(new ExpireBookingHoldCommand(ref.bookingCode(), ref.reservationCode(), at)));
        int processed = 0;
        for (var candidate : candidates) {
            try {
                if (expiry.expire(candidate)) processed++;
            } catch (RuntimeException failure) {
                // Proxy đã rollback dòng lỗi; MDC do scheduler cấp vẫn còn cho tóm tắt và stack trace.
                LOG.error("{} bookingCode={} reservationCode={}", FailureSummary.format(failure, null, null),
                        safeReference(candidate.bookingCode()), safeReference(candidate.reservationCode()), failure);
            }
        }
        return processed;
    }
    /** Chỉ ghi mã ngắn an toàn, không đưa chuỗi từ hold cũ không rõ nguồn vào log. */
    private static String safeReference(String code) {
        return code.matches("[A-Za-z0-9-]{1,64}") ? code : "-";
    }
}
