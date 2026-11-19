package com.carrental.booking.application.service;

import com.carrental.booking.application.port.in.GetBookingUseCase;
import com.carrental.booking.application.port.in.SearchBookingsUseCase;
import com.carrental.booking.application.query.BookingPagePosition;
import com.carrental.booking.application.query.SearchBookingsQuery;
import com.carrental.booking.application.view.BookingsPage;
import com.carrental.booking.application.port.out.ReadBookingPort;
import com.carrental.booking.application.query.GetBookingQuery;
import com.carrental.booking.application.view.BookingDetail;
import com.carrental.identity.api.CallerDirectory;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.shared.validation.Validations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Đọc snapshot đã lưu theo BR-122/ADR-0014, không định giá hoặc gia hạn giữ chỗ khi xem đơn. */
@Service
class BookingQueryService implements GetBookingUseCase, SearchBookingsUseCase {
    private final ReadBookingPort reads;
    private final CallerDirectory callers;

    /** Nhận nguồn đơn và danh tính từ cổng được phép, không đọc JWT tại application. */
    BookingQueryService(ReadBookingPort reads, CallerDirectory callers) {
        this.reads = reads;
        this.callers = callers;
    }

    /** Kiểm CUSTOMER tại use case và lọc chủ ngay trong truy vấn; đơn hết hạn vẫn đọc được. */
    @Override
    @Transactional(readOnly = true)
    public BookingDetail get(GetBookingQuery query) {
        var caller = callers.current();
        if (!caller.hasRole("CUSTOMER")) throw DomainException.forbidden(ErrorCode.ACCESS_DENIED);
        Validations.required(query, "query");
        return reads.loadOwned(query.code(), caller.accountCode()).map(BookingDetail::fromBooking)
                .orElseThrow(() -> DomainException.notFound(ErrorCode.BOOKING_NOT_FOUND));
    }

    /** Quyền kiểm trước cursor; lấy dư một hàng để xác định trang tiếp mà không COUNT toàn bộ lịch sử. */
    @Override
    @Transactional(readOnly = true)
    public BookingsPage search(SearchBookingsQuery query) {
        var caller = callers.current();
        if (!caller.hasRole("CUSTOMER")) throw DomainException.forbidden(ErrorCode.ACCESS_DENIED);
        Validations.required(query, "query");
        var after = BookingCursorCodec.decode(query.cursor(), caller.accountCode());
        var rows = reads.searchOwned(caller.accountCode(), after, query.limit() + 1);
        boolean hasMore = rows.size() > query.limit();
        var items = rows.subList(0, Math.min(rows.size(), query.limit()));
        String next = null;
        if (hasMore) {
            var last = items.getLast();
            next = BookingCursorCodec.encode(new BookingPagePosition(last.createdAt(), last.code()), caller.accountCode());
        }
        return new BookingsPage(items, next, hasMore);
    }
}
