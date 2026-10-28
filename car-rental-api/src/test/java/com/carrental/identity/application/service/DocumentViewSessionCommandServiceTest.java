package com.carrental.identity.application.service;

import com.carrental.audit.api.AuditDirectory;
import com.carrental.identity.api.CallerDirectory;
import com.carrental.identity.api.CallerRef;
import com.carrental.identity.application.command.OpenDocumentViewSessionCommand;
import com.carrental.identity.application.port.out.DocumentNumberCryptoPort;
import com.carrental.identity.application.port.out.ReadDocumentSubmissionPort;
import com.carrental.identity.application.view.DocumentReviewDetail;
import com.carrental.identity.application.view.DocumentStatusDetail;
import com.carrental.identity.domain.*;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.storage.api.DocumentStorageDirectory;
import com.carrental.storage.api.SignedImageRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** BR-130: quyền và thứ tự audit/giải mã/ký nằm ở use case; controller không thể thay thế kiểm này. */
class DocumentViewSessionCommandServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-28T03:00:00Z");
    private static final OpenDocumentViewSessionCommand COMMAND = OpenDocumentViewSessionCommand.from("GT-DOC001", "request-1");
    private final CallerDirectory callers = mock(CallerDirectory.class);
    private final ReadDocumentSubmissionPort reads = mock(ReadDocumentSubmissionPort.class);
    private final AuditDirectory audit = mock(AuditDirectory.class);
    private final DocumentNumberCryptoPort crypto = mock(DocumentNumberCryptoPort.class);
    private final DocumentStorageDirectory storage = mock(DocumentStorageDirectory.class);
    private final DocumentViewSessionCommandService service = new DocumentViewSessionCommandService(
            callers, reads, audit, crypto, storage, Clock.fixed(NOW, ZoneOffset.UTC));
    private final DocumentReviewDetail document = document("GT-DOC001");
    private final DocumentNumber number = new DocumentNumber("000000000000");
    private final SignedImageRef front = new SignedImageRef("https://local.invalid/front?signature=fake", NOW.plusSeconds(60));
    private final SignedImageRef back = new SignedImageRef("https://local.invalid/back?signature=fake", NOW.plusSeconds(60));

    /** Caller khác chủ; hồ sơ và URL đều giả, không đọc môi trường hay lưu credential thật. */
    @BeforeEach void setUp() {
        when(callers.current()).thenReturn(new CallerRef("TK-ACTOR1", Set.of("DOCUMENT_VERIFIER"), null));
        when(reads.findForReviewByCode(COMMAND.code())).thenReturn(Optional.of(document));
        when(crypto.reveal(any(), any())).thenReturn(number);
        when(storage.createView(document.frontImage().sealedObjectKey())).thenReturn(front);
        when(storage.createView(document.backImage().sealedObjectKey())).thenReturn(back);
    }

    /** Hai vai cấp công ty được mở hồ sơ người khác; audit hoàn tất trước cả giải mã và ký URL. */
    @ParameterizedTest @ValueSource(strings = {"ADMIN", "DOCUMENT_VERIFIER"})
    void opensAfterAuditWithCorrectActorOwnerAndAad(String role) {
        when(callers.current()).thenReturn(new CallerRef("TK-ACTOR1", Set.of(role), null));
        var result = service.open(COMMAND);
        assertEquals("TK-OWNER1", result.accountCode()); assertEquals(document.submission(), result.submission());
        assertEquals(number, result.number()); assertEquals(document.fullName(), result.fullName());
        assertEquals(document.birthDate(), result.birthDate()); assertNull(result.licenseClass()); assertNull(result.automaticOnly());
        assertEquals(front, result.frontImage()); assertEquals(back, result.backImage());
        assertEquals("DocumentViewSessionDetail[redacted]", result.toString());
        var order = inOrder(callers, reads, audit, crypto, storage);
        order.verify(callers).current(); order.verify(reads).findForReviewByCode(COMMAND.code());
        order.verify(audit).recordDocumentView("TK-ACTOR1", "TK-OWNER1", COMMAND.code(), COMMAND.requestId());
        order.verify(crypto).reveal(document.number(), new DocumentProtectionContext("TK-OWNER1", DocumentType.IDENTITY_CARD, COMMAND.code()));
        order.verify(storage).createView(document.frontImage().sealedObjectKey());
        order.verify(storage).createView(document.backImage().sealedObjectKey());
        verifyNoMoreInteractions(reads, audit, crypto, storage);
    }

    /** Mọi vai còn lại bị chặn trước tra cứu, kể cả khách là chủ hoặc nhân viên có chi nhánh. */
    @ParameterizedTest @EnumSource(value = AccountRole.class, names = {"ADMIN", "DOCUMENT_VERIFIER"}, mode = EnumSource.Mode.EXCLUDE)
    void rejectsEveryOtherRoleBeforeReading(AccountRole role) {
        when(callers.current()).thenReturn(new CallerRef("TK-OWNER1", Set.of(role.name()), 99L));
        var error = assertThrows(DomainException.class, () -> service.open(COMMAND));
        assertEquals(ErrorCode.ACCESS_DENIED, error.errorCode()); assertEquals(DomainException.Category.FORBIDDEN, error.category());
        verifyNoInteractions(reads, audit, crypto, storage);
    }

    /** Có thêm vai khách không làm mất vai xác minh, cũng không buộc consent hoặc chi nhánh. */
    @Test void permitsVerifierWhoIsAlsoCustomer() {
        when(callers.current()).thenReturn(new CallerRef("TK-ACTOR1", Set.of("CUSTOMER", "DOCUMENT_VERIFIER"), null));
        assertEquals("TK-OWNER1", service.open(COMMAND).accountCode());
    }

    /** Ngoài request giữ lỗi rõ của CallerDirectory, không có audit hoặc dữ liệu cấp theo quyền mặc định. */
    @Test void rejectsMissingRequestContext() {
        var failure = new IllegalStateException("CallerDirectory requires an active HTTP request.");
        when(callers.current()).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.open(COMMAND)));
        verifyNoInteractions(reads, audit, crypto, storage);
    }

    /** Mã không có trả DOCUMENT_NOT_FOUND, không ghi lượt mở cho tài nguyên không tồn tại. */
    @Test void rejectsMissingDocumentWithoutAudit() {
        when(reads.findForReviewByCode(COMMAND.code())).thenReturn(Optional.empty());
        var error = assertThrows(DomainException.class, () -> service.open(COMMAND));
        assertEquals(ErrorCode.DOCUMENT_NOT_FOUND, error.errorCode()); assertEquals(DomainException.Category.NOT_FOUND, error.category());
        verifyNoInteractions(audit, crypto, storage);
    }

    /** Adapter trả nhầm phiên bản là lỗi nội bộ; không giải mã hoặc trả dữ liệu của mã khác. */
    @Test void rejectsInconsistentDocumentCode() {
        when(reads.findForReviewByCode(COMMAND.code())).thenReturn(Optional.of(document("GT-OTHER1")));
        assertThrows(IllegalStateException.class, () -> service.open(COMMAND));
        verifyNoInteractions(audit, crypto, storage);
    }

    /** Audit ném lỗi, gồm lỗi commit, thì không được chạm số rõ hoặc tạo credential xem ảnh. */
    @Test void auditFailurePreventsDecryptionAndSigning() {
        var failure = new IllegalStateException("Audit commit failed.");
        doThrow(failure).when(audit).recordDocumentView(anyString(), anyString(), anyString(), anyString());
        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.open(COMMAND)));
        verifyNoInteractions(crypto, storage);
        verify(audit).recordDocumentView("TK-ACTOR1", "TK-OWNER1", COMMAND.code(), COMMAND.requestId());
    }

    /** Lỗi giải mã giữ audit đã ghi và không thử ký URL hoặc dùng số rỗng. */
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void decryptionFailureDoesNotSign(boolean returnsNull) {
        if (returnsNull) when(crypto.reveal(any(), any())).thenReturn(null);
        else when(crypto.reveal(any(), any())).thenThrow(new IllegalStateException("Document decryption failed."));
        assertThrows(IllegalStateException.class, () -> service.open(COMMAND));
        verify(audit).recordDocumentView("TK-ACTOR1", "TK-OWNER1", COMMAND.code(), COMMAND.requestId());
        verifyNoInteractions(storage);
    }

    /** Không trả một phần response khi ký mặt trước hoặc mặt sau thất bại, không tự thử lại lượt mở. */
    @ParameterizedTest @EnumSource(DocumentSide.class)
    void signingFailureDoesNotReturnPartialSession(DocumentSide side) {
        String key = side == DocumentSide.FRONT ? document.frontImage().sealedObjectKey() : document.backImage().sealedObjectKey();
        var failure = new IllegalStateException("Document storage failed.");
        when(storage.createView(key)).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.open(COMMAND)));
        verify(audit).recordDocumentView("TK-ACTOR1", "TK-OWNER1", COMMAND.code(), COMMAND.requestId());
        verify(storage).createView(key);
    }

    /** Kết quả storage thiếu/hết hạn là lỗi nội bộ, không gán URL hoặc TTL mặc định cho dễ chạy. */
    @ParameterizedTest @ValueSource(strings = {"null", "url-null", "blank", "expiry-null", "expired", "exact-now"})
    void rejectsUnusableViewLinks(String kind) {
        SignedImageRef invalid = switch (kind) {
            case "null" -> null;
            case "url-null" -> new SignedImageRef(null, NOW.plusSeconds(60));
            case "blank" -> new SignedImageRef(" ", NOW.plusSeconds(60));
            case "expiry-null" -> new SignedImageRef(front.url(), null);
            case "expired" -> new SignedImageRef(front.url(), NOW.minusSeconds(1));
            default -> new SignedImageRef(front.url(), NOW);
        };
        when(storage.createView(document.frontImage().sealedObjectKey())).thenReturn(invalid);
        assertThrows(IllegalStateException.class, () -> service.open(COMMAND));
        verify(storage, never()).createView(document.backImage().sealedObjectKey());
    }

    /** Cùng requestId vẫn là hai lượt mở, không cache view hoặc biến requestId thành idempotency key. */
    @Test void eachOpenRecordsOneEventEvenWithSameRequestId() {
        service.open(COMMAND); service.open(COMMAND);
        verify(audit, times(2)).recordDocumentView("TK-ACTOR1", "TK-OWNER1", COMMAND.code(), COMMAND.requestId());
        verify(crypto, times(2)).reveal(any(), any());
        verify(storage, times(2)).createView(document.frontImage().sealedObjectKey());
        verify(storage, times(2)).createView(document.backImage().sealedObjectKey());
    }

    /** Đầu vào sai không được phản chiếu giá trị mã GT vào thông báo lỗi. */
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" GT-DOC001", "GT-DOC001 ", "1", "gt-doc001", "GT-ABC12", "GT-ABC1234", "GT-ABC12\n"})
    void rejectsMalformedCode(String code) {
        var error = assertThrows(DomainException.class, () -> OpenDocumentViewSessionCommand.from(code, "request-1"));
        assertEquals(ErrorCode.INVALID_REQUEST, error.errorCode());
    }

    /** requestId là đầu vào kỹ thuật đã chuẩn hoá; newline hoặc quá dài phải bị chặn trước audit. */
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"bad\nline", "bad\rline", "has space", "has_underscore", "é", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void rejectsUnsafeRequestId(String requestId) {
        assertThrows(IllegalArgumentException.class, () -> OpenDocumentViewSessionCommand.from("GT-DOC001", requestId));
    }

    /** Hai biên độ dài đã duyệt của requestId đều hợp lệ. */
    @Test void acceptsRequestIdLengthBounds() {
        assertEquals("a", OpenDocumentViewSessionCommand.from("GT-DOC001", "a").requestId());
        assertEquals(64, OpenDocumentViewSessionCommand.from("GT-DOC001", "A".repeat(64)).requestId().length());
    }

    /** Hồ sơ giả đủ hai mặt sealed; số mã hoá không được dùng làm số rõ trong kết quả. */
    private static DocumentReviewDetail document(String code) {
        var status = new DocumentStatusDetail(code, DocumentType.IDENTITY_CARD, DocumentSubmissionStatus.PENDING,
                LocalDate.of(2030, 1, 1), NOW.minusSeconds(10), null, null, null);
        return new DocumentReviewDetail("TK-OWNER1", status, new ProtectedDocumentNumber(1, "nonce", "ciphertext", "hash"),
                "LOCAL FAKE DOCUMENT", LocalDate.of(1990, 1, 1), null, null,
                new DocumentImage("AN-FRONT1", DocumentSide.FRONT, "sealed/00000000-0000-0000-0000-000000000001", "image/png", 100, 10, 10),
                new DocumentImage("AN-BACK01", DocumentSide.BACK, "sealed/00000000-0000-0000-0000-000000000002", "image/png", 100, 10, 10));
    }
}
