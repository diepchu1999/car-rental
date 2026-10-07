package com.carrental.branch.adapter.in.internal;

import com.carrental.branch.api.BranchSearchView;
import com.carrental.branch.application.query.ListNearbyBranchesQuery;
import com.carrental.branch.application.view.BranchDistanceSummary;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Kiểm ánh xạ cross-module và việc không nuốt lỗi; không dùng database. */
class BranchNearbyDirectoryTest {
    /** Giữ đầy đủ tên/địa chỉ, khoảng cách mét, thứ tự và chỉ gọi use case một lần. */
    @Test
    void mapsViewsWithoutChangingDistanceOrOrder() {
        List<ListNearbyBranchesQuery> calls = new ArrayList<>();
        BranchDirectoryAdapter adapter = new BranchDirectoryAdapter(query -> {
            throw new AssertionError("Code lookup is not expected.");
        }, query -> {
            calls.add(query);
            return List.of(new BranchDistanceSummary(8, "CN-NEAR01", " Near ", " 123 Street ", 12.3456789),
                    new BranchDistanceSummary(9, "CN-NEAR02", "Far", "456 Street", 102.75));
        });
        List<BranchSearchView> result = adapter.findWithinRadius(10.5, 106.5, 1500.0);
        assertEquals(List.of(
                new BranchSearchView(8, "CN-NEAR01", " Near ", " 123 Street ", 12.3456789),
                new BranchSearchView(9, "CN-NEAR02", "Far", "456 Street", 102.75)), result);
        assertEquals(List.of(ListNearbyBranchesQuery.from(10.5, 106.5, 1500.0)), calls);
        assertThrows(UnsupportedOperationException.class, result::clear);
    }

    /** Kết quả rỗng là hợp lệ khi không có chi nhánh trong bán kính. */
    @Test
    void returnsEmptyWhenNoBranchMatches() {
        BranchDirectoryAdapter adapter = new BranchDirectoryAdapter(query -> {
            throw new AssertionError("Code lookup is not expected.");
        }, query -> List.of());
        assertEquals(List.of(), adapter.findWithinRadius(0.0, 0.0, 1.0));
    }

    /** Lỗi use case phải đi nguyên trạng ra ngoài, không bị đổi thành danh sách rỗng. */
    @Test
    void propagatesFailureWithoutRetrying() {
        IllegalStateException expected = new IllegalStateException("Simulated read failure.");
        int[] calls = {0};
        BranchDirectoryAdapter adapter = new BranchDirectoryAdapter(query -> {
            throw new AssertionError("Code lookup is not expected.");
        }, query -> { calls[0]++; throw expected; });
        assertSame(expected, assertThrowsExactly(IllegalStateException.class,
                () -> adapter.findWithinRadius(10.0, 106.0, 1000.0)));
        assertEquals(1, calls[0]);
    }

    /** Đầu vào sai bị chặn trước khi use case hoặc persistence được gọi. */
    @Test
    void rejectsInvalidInputBeforeCallingUseCase() {
        BranchDirectoryAdapter adapter = new BranchDirectoryAdapter(query -> {
            throw new AssertionError("Code lookup is not expected.");
        }, query -> { throw new AssertionError("Invalid query must not reach the use case."); });
        DomainException failure = assertThrowsExactly(DomainException.class,
                () -> adapter.findWithinRadius(10.0, 106.0, -1.0));
        assertEquals(ErrorCode.INVALID_REQUEST, failure.errorCode());
    }
}
