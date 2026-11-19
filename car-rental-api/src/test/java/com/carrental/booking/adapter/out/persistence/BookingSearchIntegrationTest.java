package com.carrental.booking.adapter.out.persistence;

import com.carrental.PostgresTestConfiguration;
import com.carrental.booking.application.port.in.SearchBookingsUseCase;
import com.carrental.booking.application.query.SearchBookingsQuery;
import com.carrental.booking.application.view.BookingsPage;
import com.carrental.booking.domain.BookingStatus;
import com.carrental.identity.api.CallerDirectory;
import com.carrental.identity.api.CallerRef;
import com.carrental.shared.sql.SqlLoader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Đi qua use case, adapter và PostgreSQL thật; mỗi lần đọc là transaction độc lập.
 * Seed header lịch sử CANCELLED trực tiếp để kiểm read model, không giả lập luồng tạo/hủy đơn.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
class BookingSearchIntegrationTest {
    private static final Instant CREATED = Instant.parse("2026-11-10T03:00:00.123456Z");
    @Autowired private SearchBookingsUseCase search;
    @Autowired private NamedParameterJdbcTemplate jdbc;
    @Autowired private SqlLoader sql;
    @MockitoBean private CallerDirectory callers;

    /** ID tăng theo thứ tự chèn ngược mã; keyset phải dùng mã và không lộ hàng của chủ khác. */
    @Test
    void ordersByTimestampThenBusinessCodeAndRestrictsOwnerAcrossPages() {
        var owner = "TK-BPG001";
        seed("BPA003", owner, CREATED);
        seed("BPA002", owner, CREATED);
        seed("BPA001", owner, CREATED);
        seed("BPA004", owner, CREATED.plusSeconds(1));
        seed("BPA005", "TK-BPG002", CREATED.plusSeconds(2));
        var first = page(owner, 2, null);
        assertEquals(List.of("DT-2611-BPA004", "DT-2611-BPA003"), codes(first));
        assertTrue(first.hasMore());
        var second = page(owner, 2, first.nextCursor());
        assertEquals(List.of("DT-2611-BPA002", "DT-2611-BPA001"), codes(second));
        assertFalse(second.hasMore());
        assertNull(second.nextCursor());
        var row = second.items().getFirst();
        assertEquals(CREATED, row.createdAt());
        assertEquals(BookingStatus.CANCELLED, row.status());
        assertEquals(1_100_000L, row.totalAmount());
        assertEquals(330_000L, row.deposit());
        assertEquals(770_000L, row.balance());
        assertEquals("XE-TEST01", row.vehicleCode());
        assertEquals("CN-TEST01", row.branchCode());
        assertEquals(Instant.parse("2026-11-13T03:00:00Z"), row.startAt());
        assertEquals(Instant.parse("2026-11-14T03:00:00Z"), row.endAt());
        assertEquals(CREATED.plusSeconds(2220), row.holdExpiresAt());
    }

    /** Chèn phía trước cursor giữa hai transaction không đẩy hàng cũ sang trang sau lần nữa. */
    @Test
    void newRowsBeforeCursorDoNotRepeatOrSkipExistingRows() {
        var owner = "TK-BPG003";
        seed("BPB003", owner, CREATED);
        seed("BPB002", owner, CREATED);
        seed("BPB001", owner, CREATED);
        var first = page(owner, 2, null);
        seed("BPB004", owner, CREATED);
        seed("BPB005", owner, CREATED.plusSeconds(1));
        var second = page(owner, 2, first.nextCursor());
        assertEquals(List.of("DT-2611-BPB003", "DT-2611-BPB002"), codes(first));
        assertEquals(List.of("DT-2611-BPB001"), codes(second));
        assertFalse(second.hasMore());
        assertEquals(List.of("DT-2611-BPB005", "DT-2611-BPB004"), codes(page(owner, 2, null)));
    }

    /** LIMIT thực ở SQL: mặc định 20, tối đa 100 và hàng nhìn trước không mất khi nối trang. */
    @Test
    void honorsDefaultAndMaximumPageSizes() {
        var owner = "TK-BPG004";
        for (int i = 1; i <= 102; i++) seed("BP" + String.format(java.util.Locale.ROOT, "%04d", i), owner, CREATED);
        var first = page(owner, null, null);
        assertEquals(20, first.items().size());
        assertTrue(first.hasMore());
        var rest = page(owner, 100, first.nextCursor());
        assertEquals(82, rest.items().size());
        assertFalse(rest.hasMore());
        var all = new ArrayList<>(codes(first));
        all.addAll(codes(rest));
        assertEquals(102, Set.copyOf(all).size());
        assertEquals("DT-2611-BP0102", all.getFirst());
        assertEquals("DT-2611-BP0001", all.getLast());
        var maximum = page(owner, 100, null);
        assertEquals(100, maximum.items().size());
        assertTrue(maximum.hasMore());
        assertEquals(2, page(owner, 100, maximum.nextCursor()).items().size());
    }

    /** Không có đơn riêng thì trả trang rỗng dù các chủ khác có dữ liệu. */
    @Test
    void returnsEmptyPageForAnotherCustomer() {
        seed("BPC001", "TK-BPG005", CREATED);
        var result = page("TK-BPG006", 20, null);
        assertTrue(result.items().isEmpty());
        assertNull(result.nextCursor());
        assertFalse(result.hasMore());
    }

    /** Mỗi lần gọi use case lấy lại chủ từ ngữ cảnh, không truyền chủ vào query. */
    private BookingsPage page(String owner, Integer limit, String cursor) {
        when(callers.current()).thenReturn(new CallerRef(owner, Set.of("CUSTOMER"), null));
        return search.search(SearchBookingsQuery.from(limit, cursor));
    }

    /** Tạo dữ liệu lịch sử đã commit; không chạm bảng khóa lịch hoặc dữ liệu ngoài container test. */
    private void seed(String suffix, String owner, Instant created) {
        var params = BookingDatabaseFixtures.header()
                .addValue("code", "DT-2611-" + suffix).addValue("customer_account_code", owner)
                .addValue("quote_code", "BG-2611-" + suffix).addValue("reservation_code", "KL-" + suffix)
                .addValue("status", "CANCELLED").addValue("created_at", created.toString())
                .addValue("status_changed_at", created.plusSeconds(1).toString())
                .addValue("hold_expires_at", created.plusSeconds(2220).toString());
        assertNotNull(jdbc.queryForObject(sql.load("sql/booking/insert_booking_for_constraint_test.sql"), params, Long.class));
    }

    /** So thứ tự công khai bằng mã thay vì dựa vào ID của hàng seed. */
    private static List<String> codes(BookingsPage page) {
        return page.items().stream().map(item -> item.code()).toList();
    }
}
