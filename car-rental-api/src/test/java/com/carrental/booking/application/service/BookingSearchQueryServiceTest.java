package com.carrental.booking.application.service;

import com.carrental.booking.application.port.out.ReadBookingPort;
import com.carrental.booking.application.query.BookingPagePosition;
import com.carrental.booking.application.query.SearchBookingsQuery;
import com.carrental.booking.application.view.BookingListItem;
import com.carrental.booking.application.view.BookingsPage;
import com.carrental.booking.domain.BookingStatus;
import com.carrental.identity.api.CallerDirectory;
import com.carrental.identity.api.CallerRef;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Kiểm quyền tại use case và lấy dư một hàng mà không bỏ mất hàng đầu trang sau. */
class BookingSearchQueryServiceTest {
    private final ReadBookingPort reads = mock(ReadBookingPort.class);
    private final CallerDirectory callers = mock(CallerDirectory.class);
    private final BookingQueryService service = new BookingQueryService(reads, callers);

    /** Chủ luôn do CallerDirectory cung cấp. */
    @BeforeEach
    void setUp() {
        when(callers.current()).thenReturn(new CallerRef("TK-OWNER1", Set.of("CUSTOMER"), null));
    }

    /** Trang trống có metadata rõ ràng và chỉ lấy tối đa 21 hàng cho limit mặc định. */
    @Test
    void returnsEmptyFirstPage() {
        when(reads.searchOwned("TK-OWNER1", null, 21)).thenReturn(List.of());
        assertEquals(new BookingsPage(List.of(), null, false), service.search(SearchBookingsQuery.from(null, null)));
        verify(reads).searchOwned("TK-OWNER1", null, 21);
    }

    /** Cursor lấy từ hàng đã trả cuối cùng; đổi limit vẫn giữ đúng vị trí. */
    @Test
    void continuesAfterLastReturnedRowAndAllowsChangingLimit() {
        var first = item("AAA003");
        var second = item("AAA002");
        var third = item("AAA001");
        when(reads.searchOwned("TK-OWNER1", null, 3)).thenReturn(List.of(first, second, third));
        var page = service.search(SearchBookingsQuery.from(2, null));
        assertEquals(List.of(first, second), page.items());
        assertTrue(page.hasMore());
        var position = new BookingPagePosition(second.createdAt(), second.code());
        assertEquals(position, BookingCursorCodec.decode(page.nextCursor(), "TK-OWNER1"));
        when(reads.searchOwned("TK-OWNER1", position, 2)).thenReturn(List.of(third));
        var last = service.search(SearchBookingsQuery.from(1, page.nextCursor()));
        assertEquals(List.of(third), last.items());
        assertFalse(last.hasMore());
        assertNull(last.nextCursor());
        verify(reads).searchOwned("TK-OWNER1", position, 2);
    }

    /** Đủ đúng limit chưa chứng minh có trang sau. */
    @Test
    void doesNotCreateCursorWithoutLookaheadRow() {
        when(reads.searchOwned("TK-OWNER1", null, 3)).thenReturn(List.of(item("AAA002"), item("AAA001")));
        var page = service.search(SearchBookingsQuery.from(2, null));
        assertEquals(2, page.items().size());
        assertFalse(page.hasMore());
        assertNull(page.nextCursor());
    }

    /** Vai nhân viên không được dùng cổng danh sách của khách. */
    @ParameterizedTest @ValueSource(strings = {"ADMIN", "BRANCH_STAFF", "DOCUMENT_VERIFIER"})
    void rejectsNonCustomerBeforeDatabase(String role) {
        when(callers.current()).thenReturn(new CallerRef("TK-OWNER1", Set.of(role), null));
        var failure = assertThrows(DomainException.class, () -> service.search(SearchBookingsQuery.from(20, null)));
        assertEquals(ErrorCode.ACCESS_DENIED, failure.errorCode());
        verifyNoInteractions(reads);
    }

    /** Cursor khách khác không được sử dụng dù người gọi cũng là CUSTOMER. */
    @Test
    void rejectsOtherOwnersCursorBeforeDatabase() {
        var cursor = BookingCursorCodec.encode(new BookingPagePosition(item("AAA001").createdAt(), "DT-2611-AAA001"), "TK-OTHER1");
        var failure = assertThrows(DomainException.class, () -> service.search(SearchBookingsQuery.from(20, cursor)));
        assertEquals(ErrorCode.INVALID_REQUEST, failure.errorCode());
        verifyNoInteractions(reads);
    }

    /** Không giả danh khách khi gọi ngoài HTTP. */
    @Test
    void propagatesMissingRequestContext() {
        var failure = new IllegalStateException("CallerDirectory requires an active HTTP request.");
        when(callers.current()).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.search(SearchBookingsQuery.from(20, null))));
        verifyNoInteractions(reads);
    }

    /** Danh sách trả về không đổi khi nguồn dữ liệu bị sửa sau khi tạo page. */
    @Test
    void copiesPageItems() {
        var items = new ArrayList<>(List.of(item("AAA001")));
        var page = new BookingsPage(items, null, false);
        items.clear();
        assertEquals(1, page.items().size());
        assertThrows(UnsupportedOperationException.class, () -> page.items().clear());
    }

    /** Snapshot tối thiểu cho test phân trang, không gọi lại pricing. */
    private static BookingListItem item(String suffix) {
        var at = Instant.parse("2026-11-10T03:00:00Z");
        return new BookingListItem("DT-2611-" + suffix, "XE-TEST01", "CN-TEST01", at.plusSeconds(86400),
                at.plusSeconds(172800), BookingStatus.CANCELLED, 1_100_000, 330_000, 770_000,
                at.plusSeconds(3600), at);
    }
}
