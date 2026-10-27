package com.carrental.identity.application.service;

import com.carrental.identity.api.CallerDirectory;
import com.carrental.identity.api.CallerRef;
import com.carrental.identity.application.port.out.ReadAccountPort;
import com.carrental.identity.application.port.out.ReadDocumentSubmissionPort;
import com.carrental.identity.application.query.GetDocumentStatusQuery;
import com.carrental.identity.application.view.DocumentHeadSummary;
import com.carrental.identity.application.view.DocumentStatusDetail;
import com.carrental.identity.domain.*;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** BR-120/130: kiểm quyền ở use case, không dựa vào việc controller đã chặn vai. */
class DocumentStatusQueryServiceTest {
    private static final String OWNER = "TK-SELF01";
    private static final GetDocumentStatusQuery QUERY = GetDocumentStatusQuery.from("GT-LOCAL1");
    private final CallerDirectory callers = mock(CallerDirectory.class);
    private final ReadAccountPort accounts = mock(ReadAccountPort.class);
    private final ReadDocumentSubmissionPort documents = mock(ReadDocumentSubmissionPort.class);
    private final DocumentStatusQueryService service = new DocumentStatusQueryService(callers, accounts, documents);

    /** Caller và consent hợp lệ; kết quả là metadata mẫu, không có ảnh hoặc số giấy tờ. */
    @BeforeEach void setUp() {
        when(callers.current()).thenReturn(new CallerRef(OWNER, Set.of("CUSTOMER"), null));
        when(accounts.findByCode(OWNER)).thenReturn(Optional.of(account(OWNER, true)));
        when(documents.findStatusByCodeAndOwner(QUERY.code(), OWNER)).thenReturn(Optional.of(detail(QUERY.code())));
        when(documents.listHeadsByOwner(OWNER)).thenReturn(List.of(new DocumentHeadSummary(
                DocumentType.IDENTITY_CARD, detail(QUERY.code()), null)));
    }

    /** Dùng caller làm chủ cho cả hai đường đọc, kiểm account trước mọi truy vấn giấy tờ. */
    @Test void readsUsingAuthenticatedOwner() {
        assertEquals(detail(QUERY.code()), service.get(QUERY));
        var order = inOrder(callers, accounts, documents);
        order.verify(callers).current(); order.verify(accounts).findByCode(OWNER);
        order.verify(documents).findStatusByCodeAndOwner(QUERY.code(), OWNER);
        assertEquals(1, service.list().size());
        verify(documents).listHeadsByOwner(OWNER);
    }

    /** Mọi vai không có CUSTOMER bị chặn trước đọc account/giấy tờ, ở cả get và list. */
    @ParameterizedTest @EnumSource(value = AccountRole.class, names = "CUSTOMER", mode = EnumSource.Mode.EXCLUDE)
    void rejectsNonCustomer(AccountRole role) {
        when(callers.current()).thenReturn(new CallerRef(OWNER, Set.of(role.name()), null));
        assertBothFailures(ErrorCode.ACCESS_DENIED, DomainException.Category.FORBIDDEN);
        verifyNoInteractions(accounts, documents);
    }

    /** CUSTOMER kiêm ADMIN vẫn chỉ đọc theo chính account của mình, không mở quyền đọc tuỳ ý. */
    @Test void permitsCustomerWithStaffRoleForOwnDocuments() {
        when(callers.current()).thenReturn(new CallerRef(OWNER, Set.of("CUSTOMER", "ADMIN"), null));
        assertEquals(QUERY.code(), service.get(QUERY).code());
        assertEquals(1, service.list().size());
        verify(documents).findStatusByCodeAndOwner(QUERY.code(), OWNER);
        verify(documents).listHeadsByOwner(OWNER);
    }

    /** Ngoài HTTP giữ nguyên lỗi rõ của caller, không dùng tài khoản rỗng hoặc quyền mặc định. */
    @Test void propagatesMissingRequestContext() {
        var failure = new IllegalStateException("CallerDirectory requires an active HTTP request.");
        when(callers.current()).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.get(QUERY)));
        assertSame(failure, assertThrows(IllegalStateException.class, service::list));
        verifyNoInteractions(accounts, documents);
    }

    /** Chưa consent hoặc account biến mất không được đọc hồ sơ; không tự tạo account trong GET use case. */
    @Test void rejectsMissingAccountOrConsent() {
        when(accounts.findByCode(OWNER)).thenReturn(Optional.empty());
        assertBothFailures(ErrorCode.ACCOUNT_NOT_FOUND, DomainException.Category.NOT_FOUND);
        when(accounts.findByCode(OWNER)).thenReturn(Optional.of(account(OWNER, false)));
        assertBothFailures(ErrorCode.CONSENT_REQUIRED, DomainException.Category.FORBIDDEN);
        verifyNoInteractions(documents);
    }

    /** Kết quả adapter sai danh tính là lỗi nội bộ, không trả dữ liệu nhầm chủ hoặc mã cho khách. */
    @Test void rejectsInconsistentAdapterResults() {
        when(accounts.findByCode(OWNER)).thenReturn(Optional.of(account("TK-OTHER1", true)));
        assertThrows(IllegalStateException.class, () -> service.get(QUERY));
        assertThrows(IllegalStateException.class, service::list);
        verifyNoInteractions(documents);
        when(accounts.findByCode(OWNER)).thenReturn(Optional.of(account(OWNER, true)));
        when(documents.findStatusByCodeAndOwner(QUERY.code(), OWNER)).thenReturn(Optional.of(detail("GT-OTHER1")));
        assertThrows(IllegalStateException.class, () -> service.get(QUERY));
    }

    /** Không tồn tại/sai chủ cùng lỗi đã chốt; chưa có head là danh sách rỗng hợp lệ. */
    @Test void returnsDocumentNotFoundOrEmptyList() {
        when(documents.findStatusByCodeAndOwner(QUERY.code(), OWNER)).thenReturn(Optional.empty());
        var failure = assertThrows(DomainException.class, () -> service.get(QUERY));
        assertEquals(ErrorCode.DOCUMENT_NOT_FOUND, failure.errorCode());
        assertEquals(DomainException.Category.NOT_FOUND, failure.category());
        when(documents.listHeadsByOwner(OWNER)).thenReturn(List.of());
        assertTrue(service.list().isEmpty());
    }

    /** Mã sai định dạng phải lỗi đầu vào; không cắt trắng, đổi hoa hoặc chấp nhận ID số. */
    @ParameterizedTest @NullAndEmptySource
    @ValueSource(strings = {" ", "1", "GT-short", "GT-LOCAL11", "gt-LOCAL1", " GT-LOCAL1", "GT-LOCAL1\n", "GT-LOCAL!"})
    void rejectsInvalidDocumentCode(String code) {
        var failure = assertThrows(DomainException.class, () -> GetDocumentStatusQuery.from(code));
        assertEquals(ErrorCode.INVALID_REQUEST, failure.errorCode());
        assertEquals(DomainException.Category.INVALID_INPUT, failure.category());
    }

    /** Query hợp lệ giữ nguyên mã, còn toString không đưa mã vào log. */
    @Test void preservesValidCodeAndRedactsQuery() {
        assertEquals("GT-LOCAL1", QUERY.code());
        assertEquals("GetDocumentStatusQuery[redacted]", QUERY.toString());
    }

    /** Kiểm cùng chính sách cho get và list, không coi mọi exception là đạt. */
    private void assertBothFailures(ErrorCode code, DomainException.Category category) {
        for (var action : List.<org.junit.jupiter.api.function.Executable>of(() -> service.get(QUERY), () -> service.list())) {
            var failure = assertThrows(DomainException.class, action);
            assertEquals(code, failure.errorCode()); assertEquals(category, failure.category());
        }
    }

    /** Account giả có hoặc không có bằng chứng điều khoản để kiểm BR-120. */
    private static Account account(String code, boolean consented) {
        return Account.restore(code, "local-test-subject", new VietnamMobileNumber("+84910000002"),
                consented ? new TermsConsent("local-test-v1", Instant.EPOCH) : null);
    }

    /** Một phiên bản pending mẫu, không dùng dữ liệu người thật. */
    private static DocumentStatusDetail detail(String code) {
        return new DocumentStatusDetail(code, DocumentType.IDENTITY_CARD, DocumentSubmissionStatus.PENDING,
                LocalDate.of(2035, 1, 1), Instant.EPOCH, null, null, null);
    }
}
