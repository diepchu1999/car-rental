package com.carrental.branch.adapter.in.internal;

import com.carrental.branch.api.BranchRef;
import com.carrental.branch.application.port.in.FindBranchByIdUseCase;
import com.carrental.branch.application.query.FindBranchByIdQuery;
import com.carrental.branch.application.view.BranchDetail;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Kiểm hợp đồng lookup ID qua adapter/in/internal theo BR-003 và ADR-0008. */
class BranchIdDirectoryTest {
    /** ID truyền nguyên sang use case, mã trả về lấy từ dữ liệu chứ không sinh từ ID. */
    @Test
    void mapsIdLookupToBranchReference() {
        var lookup = mock(FindBranchByIdUseCase.class);
        when(lookup.findById(new FindBranchByIdQuery(42))).thenReturn(Optional.of(
                new BranchDetail(42, "CN-ZYX987", 10, 106, "Branch", "Address")));
        assertEquals(Optional.of(new BranchRef(42, "CN-ZYX987")), adapter(lookup).findById(42));
        verify(lookup).findById(new FindBranchByIdQuery(42));
        verifyNoMoreInteractions(lookup);
    }

    /** Tham chiếu không tồn tại trả rỗng để module gọi quyết định cách xử lý. */
    @Test
    void returnsEmptyForMissingId() {
        var lookup = mock(FindBranchByIdUseCase.class);
        when(lookup.findById(new FindBranchByIdQuery(42))).thenReturn(Optional.empty());
        assertTrue(adapter(lookup).findById(42).isEmpty());
    }

    /** Lỗi lưu trữ không được nuốt thành kết quả rỗng hoặc tự thử lại. */
    @Test
    void propagatesLookupFailure() {
        var lookup = mock(FindBranchByIdUseCase.class);
        var failure = new IllegalStateException("Lookup failed.");
        when(lookup.findById(new FindBranchByIdQuery(42))).thenThrow(failure);
        assertSame(failure, assertThrowsExactly(IllegalStateException.class, () -> adapter(lookup).findById(42)));
        verify(lookup).findById(new FindBranchByIdQuery(42));
        verifyNoMoreInteractions(lookup);
    }

    /** Query chặn ID không dương trước use case; kiểm cả biên long nhỏ nhất. */
    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void rejectsInvalidIdBeforeUseCase(long id) {
        var lookup = mock(FindBranchByIdUseCase.class);
        var failure = assertThrowsExactly(DomainException.class, () -> adapter(lookup).findById(id));
        assertEquals(ErrorCode.INVALID_REQUEST, failure.errorCode());
        verifyNoInteractions(lookup);
    }

    /** Những đường tra khác phải thất bại nếu adapter gọi nhầm. */
    private BranchDirectoryAdapter adapter(FindBranchByIdUseCase lookup) {
        return new BranchDirectoryAdapter(query -> { throw new AssertionError("Unexpected code lookup."); },
                query -> { throw new AssertionError("Unexpected radius lookup."); }, lookup);
    }
}
