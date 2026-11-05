package com.carrental.identity.application.service;

import com.carrental.identity.api.CallerDirectory;
import com.carrental.identity.api.CallerRef;
import com.carrental.identity.application.command.RejectDocumentCommand;
import com.carrental.identity.application.command.VerifyDocumentCommand;
import com.carrental.identity.application.port.out.ReadDocumentSubmissionPort;
import com.carrental.identity.application.port.out.WriteDocumentSubmissionPort;
import com.carrental.identity.application.view.DocumentStatusDetail;
import com.carrental.identity.domain.AccountRole;
import com.carrental.identity.domain.DocumentSubmissionStatus;
import com.carrental.identity.domain.DocumentType;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** BR-130/803: kiểm quyền và điều phối, không dùng read trước write để phân xử trạng thái. */
class DocumentReviewCommandServiceTest {
    private static final String CODE = "GT-TEST01";
    private static final String REVIEWER = "TK-TEST01";
    private static final Instant NOW = Instant.parse("2026-10-30T03:00:00.123456789Z");
    private static final Instant STORED_AT = NOW.truncatedTo(ChronoUnit.MICROS);
    private CallerDirectory callers;
    private WriteDocumentSubmissionPort writes;
    private ReadDocumentSubmissionPort reads;
    private Clock clock;
    private DocumentReviewCommandService service;

    /** Mỗi test có mock riêng; mốc nano chứng minh chỉ chốt một lần ở độ chính xác PostgreSQL. */
    @BeforeEach void setUp() {
        callers = mock(CallerDirectory.class); writes = mock(WriteDocumentSubmissionPort.class);
        reads = mock(ReadDocumentSubmissionPort.class); clock = mock(Clock.class);
        service = new DocumentReviewCommandService(callers, writes, reads, clock);
        when(callers.current()).thenReturn(new CallerRef(REVIEWER, Set.of("DOCUMENT_VERIFIER"), null));
        when(clock.instant()).thenReturn(NOW);
        when(reads.findOwnerCodeByCode(CODE)).thenReturn(Optional.of("TK-OWNER1"));
    }

    /** Hai vai được duyệt, kể cả có thêm CUSTOMER; không cần chi nhánh hoặc quyền khách để xác minh. */
    @ParameterizedTest @CsvSource({"ADMIN,false", "ADMIN,true", "DOCUMENT_VERIFIER,false", "DOCUMENT_VERIFIER,true"})
    void allowedRolesVerifyWithServerIdentityAndTime(String role, boolean customer) {
        when(callers.current()).thenReturn(new CallerRef(REVIEWER,
                customer ? Set.of(role, "CUSTOMER") : Set.of(role), null));
        var command = VerifyDocumentCommand.from(CODE);
        var stored = detail(CODE, DocumentSubmissionStatus.VERIFIED, STORED_AT, null, null);
        when(reads.findStatusForReviewByCode(CODE)).thenReturn(Optional.of(stored));
        assertSame(stored, service.verify(command));
        var order = inOrder(callers, clock, writes, reads);
        order.verify(callers).current(); order.verify(reads).findOwnerCodeByCode(CODE);
        order.verify(clock).instant();
        order.verify(writes).verify(command, REVIEWER, STORED_AT);
        order.verify(reads).findStatusForReviewByCode(CODE);
        verifyNoMoreInteractions(callers, clock, writes, reads);
    }

    /** Từ chối giữ nguyên ghi chú xuống dòng, không nhận reviewer/time từ command. */
    @ParameterizedTest @ValueSource(strings = {"ADMIN", "DOCUMENT_VERIFIER"})
    void allowedRolesRejectWithoutChangingNote(String role) {
        when(callers.current()).thenReturn(new CallerRef(REVIEWER, Set.of(role), null));
        var command = RejectDocumentCommand.from(CODE, "ILLEGIBLE", "Local test\nsecond line");
        var stored = detail(CODE, DocumentSubmissionStatus.REJECTED, STORED_AT, "ILLEGIBLE", command.rejectionNote());
        when(reads.findStatusForReviewByCode(CODE)).thenReturn(Optional.of(stored));
        assertSame(stored, service.reject(command));
        var order = inOrder(callers, clock, writes, reads);
        order.verify(callers).current(); order.verify(reads).findOwnerCodeByCode(CODE);
        order.verify(clock).instant();
        order.verify(writes).reject(command, REVIEWER, STORED_AT);
        order.verify(reads).findStatusForReviewByCode(CODE);
        verifyNoMoreInteractions(callers, clock, writes, reads);
    }

    /** BR-130: kiêm CUSTOMER không cho ADMIN/verifier tự duyệt hoặc tự từ chối, trước cả lấy giờ và ghi. */
    @ParameterizedTest @CsvSource({"ADMIN,verify", "ADMIN,reject", "DOCUMENT_VERIFIER,verify", "DOCUMENT_VERIFIER,reject"})
    void deniesOwnDecisionBeforeWrite(String role, String action) {
        when(callers.current()).thenReturn(new CallerRef(REVIEWER, Set.of(role, "CUSTOMER"), null));
        when(reads.findOwnerCodeByCode(CODE)).thenReturn(Optional.of(REVIEWER));
        assertForbidden(() -> decide(action));
        verify(reads).findOwnerCodeByCode(CODE); verifyNoMoreInteractions(reads);
        verifyNoInteractions(writes, clock);
    }

    /** Mã không tồn tại trả đúng 404 ngay tại use case, không thử ghi hoặc đọc dữ liệu nhạy cảm. */
    @ParameterizedTest @ValueSource(strings = {"verify", "reject"})
    void missingOwnerReturnsNotFoundBeforeWrite(String action) {
        when(reads.findOwnerCodeByCode(CODE)).thenReturn(Optional.empty());
        var failure = assertThrows(DomainException.class, () -> decide(action));
        assertEquals(ErrorCode.DOCUMENT_NOT_FOUND, failure.errorCode());
        assertEquals(DomainException.Category.NOT_FOUND, failure.category());
        verify(reads).findOwnerCodeByCode(CODE); verifyNoMoreInteractions(reads);
        verifyNoInteractions(writes, clock);
    }

    /** Lookup quyền thất bại phải dừng, không coi chủ không đọc được là người khác. */
    @ParameterizedTest @ValueSource(strings = {"verify", "reject"})
    void ownerLookupFailureDoesNotWrite(String action) {
        var failure = new IllegalStateException("Owner lookup failed.");
        when(reads.findOwnerCodeByCode(CODE)).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> decide(action)));
        verifyNoInteractions(writes, clock);
    }

    /** Chọn thao tác test, không thay cách kiểm quyền hoặc quyết định của service. */
    private void decide(String action) {
        if (action.equals("verify")) service.verify(VerifyDocumentCommand.from(CODE));
        else service.reject(RejectDocumentCommand.from(CODE, "ILLEGIBLE", null));
    }

    /** Mọi vai khác đều bị chặn trước đọc/ghi; được gán chi nhánh cũng không cấp quyền xác minh. */
    @ParameterizedTest @EnumSource(value = AccountRole.class, names = {"ADMIN", "DOCUMENT_VERIFIER"}, mode = EnumSource.Mode.EXCLUDE)
    void deniesEveryOtherRole(AccountRole role) {
        when(callers.current()).thenReturn(new CallerRef(REVIEWER, Set.of(role.name()), role.isBranchScoped() ? 1L : null));
        assertForbidden(() -> service.verify(VerifyDocumentCommand.from(CODE)));
        assertForbidden(() -> service.reject(RejectDocumentCommand.from(CODE, "ILLEGIBLE", null)));
        verifyNoInteractions(writes, reads, clock);
    }

    /** Caller không có vai vẫn bị 403 trước cả validation nội bộ của command. */
    @Test void deniesEmptyRolesBeforeCommandInspection() {
        when(callers.current()).thenReturn(new CallerRef(REVIEWER, Set.of(), null));
        assertForbidden(() -> service.verify(null)); assertForbidden(() -> service.reject(null));
        verifyNoInteractions(writes, reads, clock);
    }

    /** Ngoài request không được giả người duyệt rỗng hoặc tự dùng SYSTEM/ADMIN. */
    @Test void propagatesMissingRequestContext() {
        var failure = new IllegalStateException("Caller is only available in an HTTP request.");
        when(callers.current()).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.verify(VerifyDocumentCommand.from(CODE))));
        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.reject(RejectDocumentCommand.from(CODE, "ILLEGIBLE", null))));
        verifyNoInteractions(writes, reads, clock);
    }

    /** Lỗi từ adapter giữ nguyên mã/cause đã an toàn, không thử lại quyết định hoặc reload sau thất bại. */
    @ParameterizedTest @EnumSource(value = ErrorCode.class, names = {"DOCUMENT_NOT_FOUND", "DOCUMENT_REVIEW_CONFLICT", "IDENTITY_CARD_ALREADY_REGISTERED"})
    void writeFailurePropagatesWithoutReload(ErrorCode code) {
        var command = VerifyDocumentCommand.from(CODE);
        var failure = code == ErrorCode.DOCUMENT_NOT_FOUND ? DomainException.notFound(code) : DomainException.conflict(code);
        doThrow(failure).when(writes).verify(command, REVIEWER, STORED_AT);
        assertSame(failure, assertThrows(DomainException.class, () -> service.verify(command)));
        verify(writes).verify(command, REVIEWER, STORED_AT); verifyNoMoreInteractions(writes);
        verify(reads).findOwnerCodeByCode(CODE); verifyNoMoreInteractions(reads);
    }

    /** Lỗi từ chối không bị đổi thành xác minh, retry hoặc trả response thành công giả. */
    @Test void rejectionFailurePropagatesWithoutReload() {
        var command = RejectDocumentCommand.from(CODE, "ILLEGIBLE", null);
        var failure = DomainException.conflict(ErrorCode.DOCUMENT_REVIEW_CONFLICT);
        doThrow(failure).when(writes).reject(command, REVIEWER, STORED_AT);
        assertSame(failure, assertThrows(DomainException.class, () -> service.reject(command)));
        verify(writes).reject(command, REVIEWER, STORED_AT); verifyNoMoreInteractions(writes);
        verify(reads).findOwnerCodeByCode(CODE); verifyNoMoreInteractions(reads);
    }

    /** Mất hàng hoặc reload sai quyết định không được trả thành công; exception không kèm read view. */
    @ParameterizedTest @ValueSource(strings = {"missing", "code", "status", "time", "reason", "note"})
    void failsOnInconsistentVerificationReload(String field) {
        var stored = detail(field.equals("code") ? "GT-OTHER1" : CODE,
                field.equals("status") ? DocumentSubmissionStatus.PENDING : DocumentSubmissionStatus.VERIFIED,
                field.equals("time") ? STORED_AT.plusSeconds(1) : STORED_AT,
                field.equals("reason") ? "ILLEGIBLE" : null, field.equals("note") ? "local-fake-note" : null);
        when(reads.findStatusForReviewByCode(CODE)).thenReturn(field.equals("missing") ? Optional.empty() : Optional.of(stored));
        var failure = assertThrows(IllegalStateException.class, () -> service.verify(VerifyDocumentCommand.from(CODE)));
        assertFalse(failure.getMessage().contains(CODE)); assertFalse(failure.getMessage().contains("local-fake-note"));
    }

    /** Ghi chú tùy chọn không bị tự điền bằng chuỗi rỗng hoặc một nội dung mặc định. */
    @Test void acceptsRejectionWithoutNote() {
        var stored = detail(CODE, DocumentSubmissionStatus.REJECTED, STORED_AT, "ILLEGIBLE", null);
        when(reads.findStatusForReviewByCode(CODE)).thenReturn(Optional.of(stored));
        assertSame(stored, service.reject(RejectDocumentCommand.from(CODE, "ILLEGIBLE", null)));
    }

    /** Reload bỏ mất ghi chú đã gửi phải làm thất bại transaction. */
    @Test void rejectsReloadThatLosesRejectionNote() {
        when(reads.findStatusForReviewByCode(CODE)).thenReturn(Optional.of(
                detail(CODE, DocumentSubmissionStatus.REJECTED, STORED_AT, "ILLEGIBLE", null)));
        assertThrows(IllegalStateException.class, () -> service.reject(RejectDocumentCommand.from(CODE, "ILLEGIBLE", "local-fake-note")));
    }

    /** Command null của caller được phép là lỗi gọi nội bộ, không ghi dữ liệu. */
    @Test void requiresCommandAfterAuthorization() {
        assertThrows(NullPointerException.class, () -> service.verify(null));
        assertThrows(NullPointerException.class, () -> service.reject(null));
        verifyNoInteractions(writes, reads, clock);
    }

    /** View metadata cố định phục vụ test, không có số giấy tờ hoặc tham chiếu ảnh. */
    private static DocumentStatusDetail detail(String code, DocumentSubmissionStatus status, Instant at, String reason, String note) {
        return new DocumentStatusDetail(code, DocumentType.IDENTITY_CARD, status, LocalDate.of(2030, 1, 1),
                NOW.minusSeconds(100), at, reason, note, null);
    }

    /** 403 phải đúng ACCESS_DENIED, không chấp nhận NPE hoặc lỗi mock thay cho kiểm quyền. */
    private static void assertForbidden(org.junit.jupiter.api.function.Executable action) {
        var failure = assertThrows(DomainException.class, action);
        assertEquals(ErrorCode.ACCESS_DENIED, failure.errorCode());
        assertEquals(DomainException.Category.FORBIDDEN, failure.category());
    }
}
