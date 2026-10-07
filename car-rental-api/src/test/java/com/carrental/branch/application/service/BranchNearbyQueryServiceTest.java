package com.carrental.branch.application.service;

import com.carrental.branch.application.port.out.ReadBranchPort;
import com.carrental.branch.application.query.GetBranchQuery;
import com.carrental.branch.application.query.ListNearbyBranchesQuery;
import com.carrental.branch.application.view.BranchDetail;
import com.carrental.branch.application.view.BranchDistanceSummary;
import com.carrental.shared.error.DomainException;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Kiểm service mở rộng vẫn dùng một cổng đọc, không tự truy vấn xe hoặc tính khoảng cách. */
class BranchNearbyQueryServiceTest {
    /** Gọi cổng đọc đúng một lần và chụp danh sách bất biến mà không đổi thứ tự. */
    @Test
    void delegatesRadiusQueryOnceAndCopiesResult() {
        ReadBranchPort port = mock(ReadBranchPort.class);
        ListNearbyBranchesQuery query = ListNearbyBranchesQuery.from(10.0, 106.0, 1500.0);
        BranchDistanceSummary view = new BranchDistanceSummary(1, "CN-NEAR01", "Near", "123 Street", 12.5);
        List<BranchDistanceSummary> mutable = new ArrayList<>(List.of(view));
        when(port.findWithinRadius(query)).thenReturn(mutable);
        List<BranchDistanceSummary> actual = new BranchQueryService(port).list(query);
        mutable.clear();
        assertEquals(List.of(view), actual);
        assertThrows(UnsupportedOperationException.class, actual::clear);
        verify(port).findWithinRadius(query);
        verifyNoMoreInteractions(port);
    }

    /** Lỗi lưu trữ không bị biến thành rỗng hoặc thử lại. */
    @Test
    void propagatesRadiusReadFailure() {
        ReadBranchPort port = mock(ReadBranchPort.class);
        ListNearbyBranchesQuery query = ListNearbyBranchesQuery.from(10.0, 106.0, 1500.0);
        RuntimeException expected = new IllegalStateException("Simulated storage failure.");
        when(port.findWithinRadius(query)).thenThrow(expected);
        assertSame(expected, assertThrowsExactly(IllegalStateException.class,
                () -> new BranchQueryService(port).list(query)));
        verify(port).findWithinRadius(query);
        verifyNoMoreInteractions(port);
    }

    /** Tra mã tùy chọn sau khi tách adapter vẫn trả nguyên view hoặc rỗng. */
    @Test
    void optionalLookupPreservesResult() {
        ReadBranchPort port = mock(ReadBranchPort.class);
        BranchDetail detail = new BranchDetail(1, "CN-LOOK01", 10, 106, "Branch", "Street");
        when(port.findByCode("CN-LOOK01")).thenReturn(Optional.of(detail));
        when(port.findByCode(" CN-MISS01 ")).thenReturn(Optional.empty());
        BranchQueryService service = new BranchQueryService(port);
        assertSame(detail, service.find(GetBranchQuery.from("CN-LOOK01")).orElseThrow());
        assertTrue(service.find(GetBranchQuery.from(" CN-MISS01 ")).isEmpty());
        verify(port).findByCode("CN-LOOK01");
        verify(port).findByCode(" CN-MISS01 ");
        verifyNoMoreInteractions(port);
    }

    /** Tra mã tùy chọn không nuốt lỗi lưu trữ sau khi tách cổng cross-module. */
    @Test
    void optionalLookupPropagatesFailure() {
        ReadBranchPort port = mock(ReadBranchPort.class);
        RuntimeException expected = new IllegalStateException("Simulated lookup failure.");
        when(port.findByCode("CN-FAIL01")).thenThrow(expected);
        assertSame(expected, assertThrowsExactly(IllegalStateException.class,
                () -> new BranchQueryService(port).find(GetBranchQuery.from("CN-FAIL01"))));
        verify(port).findByCode("CN-FAIL01");
        verifyNoMoreInteractions(port);
    }

    /** Query thiếu không được đưa tới cổng đọc ở cả ba đường đọc. */
    @Test
    void rejectsNullQueriesBeforePersistence() {
        ReadBranchPort port = mock(ReadBranchPort.class);
        BranchQueryService service = new BranchQueryService(port);
        assertThrowsExactly(DomainException.class, () -> service.list(null));
        assertThrowsExactly(DomainException.class, () -> service.find(null));
        assertThrowsExactly(DomainException.class, () -> service.get(null));
        verifyNoInteractions(port);
    }
}
