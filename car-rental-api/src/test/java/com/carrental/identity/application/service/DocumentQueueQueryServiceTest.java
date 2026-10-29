package com.carrental.identity.application.service;

import com.carrental.identity.api.CallerDirectory;
import com.carrental.identity.api.CallerRef;
import com.carrental.identity.application.port.out.ReadDocumentSubmissionPort;
import com.carrental.identity.application.query.SearchPendingDocumentsQuery;
import com.carrental.identity.application.view.PendingDocumentListItem;
import com.carrental.identity.application.view.PendingDocumentsPage;
import com.carrental.identity.domain.AccountRole;
import com.carrental.identity.domain.DocumentType;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** BR-130/803: kiểm quyền và phân trang trong use case, độc lập với controller hoặc bộ lọc HTTP. */
class DocumentQueueQueryServiceTest {
    private final CallerDirectory callers = mock(CallerDirectory.class);
    private final ReadDocumentSubmissionPort documents = mock(ReadDocumentSubmissionPort.class);
    private final DocumentQueueQueryService service = new DocumentQueueQueryService(callers, documents);

    /** Mặc định verifier không có chi nhánh; kết quả mỗi test được khai tường minh. */
    @BeforeEach void setUp() {
        when(callers.current()).thenReturn(new CallerRef("TK-ACTOR1", Set.of("DOCUMENT_VERIFIER"), null));
    }

    /** Chỉ hai vai được đọc, cả khi không có chi nhánh; kiểm caller trước gọi read port. */
    @ParameterizedTest @ValueSource(strings = {"ADMIN", "DOCUMENT_VERIFIER"})
    void permitsCompanyWideReviewRoles(String role) {
        when(callers.current()).thenReturn(new CallerRef("TK-ACTOR1", Set.of(role), null));
        when(documents.searchPending(21, 0)).thenReturn(List.of(item(1), item(2)));
        var page = service.search(SearchPendingDocumentsQuery.from(null, null));
        assertEquals(List.of(item(1), item(2)), page.items()); assertFalse(page.hasMore());
        var order = inOrder(callers, documents); order.verify(callers).current(); order.verify(documents).searchPending(21, 0);
        verifyNoMoreInteractions(documents);
    }

    /** Mười một vai còn lại phải 403 trước mọi đọc, dù giả có branch hoặc query hợp lệ. */
    @ParameterizedTest @EnumSource(value = AccountRole.class, names = {"ADMIN", "DOCUMENT_VERIFIER"}, mode = EnumSource.Mode.EXCLUDE)
    void rejectsOtherRolesBeforeReading(AccountRole role) {
        when(callers.current()).thenReturn(new CallerRef("TK-ACTOR1", Set.of(role.name()), 17L));
        var failure = assertThrows(DomainException.class, () -> service.search(SearchPendingDocumentsQuery.from(20, 0L)));
        assertEquals(ErrorCode.ACCESS_DENIED, failure.errorCode()); assertEquals(DomainException.Category.FORBIDDEN, failure.category());
        verifyNoInteractions(documents);
    }

    /** Tài khoản có CUSTOMER cùng vai verifier vẫn được đọc theo vai nhân viên, không lọc về hồ sơ chính mình. */
    @Test void customerRoleDoesNotEraseVerifierPermission() {
        when(callers.current()).thenReturn(new CallerRef("TK-ACTOR1", Set.of("CUSTOMER", "DOCUMENT_VERIFIER"), null));
        when(documents.searchPending(2, 5)).thenReturn(List.of(item(6)));
        assertEquals(List.of(item(6)), service.search(SearchPendingDocumentsQuery.from(1, 5L)).items());
        verify(documents).searchPending(2, 5);
    }

    /** Không có ngữ cảnh HTTP phải giữ lỗi của CallerDirectory, không giả danh người gọi mặc định. */
    @Test void preservesMissingRequestContextFailure() {
        var failure = new IllegalStateException("CallerDirectory requires an active HTTP request.");
        when(callers.current()).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.search(SearchPendingDocumentsQuery.from(null, null))));
        verifyNoInteractions(documents);
    }

    /** Fetch thêm đúng một hàng; không trả hàng thăm dò, không đếm lại hoặc đọc toàn bộ dữ liệu. */
    @ParameterizedTest @ValueSource(ints = {1, 20, 100})
    void returnsOnlyPageAndDetectsMoreFromExtraRow(int limit) {
        var rows = IntStream.rangeClosed(1, limit + 1).mapToObj(DocumentQueueQueryServiceTest::item).toList();
        when(documents.searchPending(limit + 1, 7)).thenReturn(rows);
        var page = service.search(SearchPendingDocumentsQuery.from(limit, 7L));
        assertEquals(rows.subList(0, limit), page.items()); assertTrue(page.hasMore());
        verify(documents).searchPending(limit + 1, 7); verifyNoMoreInteractions(documents);
    }

    /** Trang rỗng/thiếu/vừa đủ limit đều không có trang sau, không dùng size == limit để đoán. */
    @ParameterizedTest @ValueSource(ints = {0, 1, 20})
    void lastPageDoesNotClaimMore(int count) {
        var rows = IntStream.rangeClosed(1, count).mapToObj(DocumentQueueQueryServiceTest::item).toList();
        when(documents.searchPending(21, 0)).thenReturn(rows);
        var page = service.search(SearchPendingDocumentsQuery.from(null, null));
        assertEquals(rows, page.items()); assertFalse(page.hasMore());
    }

    /** Adapter lỗi không được trả trang rỗng thành công làm nhân viên hiểu lầm hết việc. */
    @Test void propagatesPersistenceFailure() {
        var failure = new IllegalStateException("Forced document read failure.");
        when(documents.searchPending(21, 0)).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.search(SearchPendingDocumentsQuery.from(null, null))));
    }

    /** Query dùng mặc định của guideline, giữ nguyên offset lớn mà không tràn số int hoặc tự cắt. */
    @Test void preservesDefaultsAndValidBounds() {
        assertEquals(new SearchPendingDocumentsQuery(20, 0L), SearchPendingDocumentsQuery.from(null, null));
        assertEquals(100, SearchPendingDocumentsQuery.from(100, Long.MAX_VALUE).limit());
        assertEquals(Long.MAX_VALUE, SearchPendingDocumentsQuery.from(100, Long.MAX_VALUE).offset());
    }

    /** 0, số âm, 101 và số rất lớn đều INVALID_REQUEST; factory và constructor không có lối bỏ qua. */
    @ParameterizedTest @ValueSource(ints = {0, -1, 101, Integer.MAX_VALUE, Integer.MIN_VALUE})
    void rejectsInvalidLimit(int limit) {
        assertInvalid(() -> SearchPendingDocumentsQuery.from(limit, 0L));
        assertInvalid(() -> new SearchPendingDocumentsQuery(limit, 0L));
    }

    /** Offset âm không được sửa thành 0; không để PostgreSQL quyết định lỗi đầu vào cho API. */
    @ParameterizedTest @ValueSource(longs = {-1, Long.MIN_VALUE})
    void rejectsNegativeOffset(long offset) { assertInvalid(() -> SearchPendingDocumentsQuery.from(20, offset)); }

    /** Page chụp dữ liệu và không cho sửa; toString không in liên kết GT/TK. */
    @Test void pageIsImmutableAndRedacted() {
        var source = new ArrayList<>(List.of(item(1)));
        var page = new PendingDocumentsPage(source, false); source.clear();
        assertEquals(1, page.items().size()); assertThrows(UnsupportedOperationException.class, () -> page.items().clear());
        assertEquals("PendingDocumentsPage[redacted]", page.toString());
        assertEquals("PendingDocumentListItem[redacted]", page.items().getFirst().toString());
    }

    /** Kiểm đúng loại lỗi, không coi mọi exception là bằng chứng validation hoạt động. */
    private static void assertInvalid(org.junit.jupiter.api.function.Executable action) {
        var failure = assertThrows(DomainException.class, action);
        assertEquals(ErrorCode.INVALID_REQUEST, failure.errorCode()); assertEquals(DomainException.Category.INVALID_INPUT, failure.category());
    }

    /** Metadata giả không chứa số giấy tờ hay URL, mỗi item có mã riêng để phát hiện sai trang/thứ tự. */
    private static PendingDocumentListItem item(int index) {
        return new PendingDocumentListItem("GT-%06d".formatted(index), "TK-OWNER1", DocumentType.IDENTITY_CARD, Instant.EPOCH.plusSeconds(index));
    }
}
