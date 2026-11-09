package com.carrental.identity.application.service;

import com.carrental.identity.api.CallerDirectory;
import com.carrental.identity.api.CallerRef;
import com.carrental.identity.application.command.SubmitDocumentCommand;
import com.carrental.identity.application.port.out.*;
import com.carrental.identity.application.view.DocumentSubmissionDetail;
import com.carrental.identity.domain.*;
import com.carrental.shared.code.BusinessCodeGenerator;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.storage.api.DocumentStorageDirectory;
import com.carrental.storage.api.StoredImageRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** BR-120/130: kiểm quyền trước storage, giữ AAD đúng GT và chỉ thử lại lỗi trùng mã. */
class DocumentSubmissionCommandServiceTest {
    private static final String OWNER = "TK-SELF01";
    private static final String FRONT = "AN-FRONT1";
    private static final String BACK = "AN-BACK01";
    private static final Instant NOW = Instant.parse("2026-10-26T03:00:00.123456789Z");
    private final CallerDirectory callers = mock(CallerDirectory.class);
    private final ReadAccountPort accounts = mock(ReadAccountPort.class);
    private final ReadDocumentUploadPort uploads = mock(ReadDocumentUploadPort.class);
    private final DocumentStorageDirectory storage = mock(DocumentStorageDirectory.class);
    private final DocumentNumberCryptoPort crypto = mock(DocumentNumberCryptoPort.class);
    private final WriteDocumentSubmissionPort writes = mock(WriteDocumentSubmissionPort.class);
    private final ReadDocumentSubmissionPort reads = mock(ReadDocumentSubmissionPort.class);
    private final BusinessCodeGenerator codes = mock(BusinessCodeGenerator.class);
    private final List<DocumentSubmission> attempts = new ArrayList<>();
    private final DocumentSubmissionCommandService service = new DocumentSubmissionCommandService(callers, accounts,
            uploads, storage, crypto, writes, reads, codes, Clock.fixed(NOW, ZoneOffset.UTC));

    /** Các mock chỉ kiểm điều phối; test PostgreSQL riêng kiểm transaction và adapter thật. */
    @BeforeEach void setUp() {
        when(callers.current()).thenReturn(new CallerRef(OWNER, Set.of("CUSTOMER"), null));
        when(accounts.findByCode(OWNER)).thenReturn(Optional.of(account(OWNER, true)));
        configureUploads(DocumentType.IDENTITY_CARD);
        when(storage.sealUpload(anyString())).thenAnswer(invocation -> new StoredImageRef(
                ((String) invocation.getArgument(0)).replace("incoming/", "sealed/"), "image/png", 100, 10, 10));
        when(codes.generate("GT")).thenReturn("GT-FIRST1");
        when(crypto.protect(any(), any())).thenAnswer(invocation -> {
            DocumentProtectionContext context = invocation.getArgument(1);
            return new ProtectedDocumentNumber(1, "fake-nonce", "fake-cipher-" + context.documentCode(), "fake-hash");
        });
        when(writes.insert(any())).thenAnswer(invocation -> { attempts.add(invocation.getArgument(0)); return true; });
        when(reads.findByCodeAndOwner(anyString(), eq(OWNER))).thenAnswer(invocation -> Optional.of(detail(attempts.getLast())));
    }

    /** Hai loại đều kiểm đủ hai AN trước chốt, dùng chủ caller, bảo vệ số và reload trước trả. */
    @ParameterizedTest @EnumSource(DocumentType.class)
    void submitsBothTypesAfterCheckingBothUploads(DocumentType type) {
        configureUploads(type);
        var command = command(type);
        var result = service.submit(command);
        var saved = attempts.getFirst();
        assertEquals(OWNER, saved.accountCode());
        assertEquals(type, saved.documentType());
        assertEquals(command.fullName(), saved.fullName());
        assertEquals(command.birthDate(), saved.birthDate());
        assertEquals(command.licenseClass(), saved.licenseClass());
        assertEquals(command.automaticOnly(), saved.automaticOnly());
        assertEquals(command.issuedOn(), saved.issuedOn());
        assertEquals(NOW.truncatedTo(ChronoUnit.MICROS), saved.submittedAt());
        assertEquals(detail(saved), result);
        assertEquals("DocumentSubmissionDetail[redacted]", result.toString());
        var order = inOrder(callers, accounts, uploads, storage, crypto, writes, reads);
        order.verify(callers).current(); order.verify(accounts).findByCode(OWNER);
        order.verify(uploads).findByCodeAndOwner(FRONT, OWNER);
        order.verify(uploads).findByCodeAndOwner(BACK, OWNER);
        order.verify(storage).sealUpload(upload(FRONT, OWNER, type, DocumentSide.FRONT).incomingObjectKey());
        order.verify(storage).sealUpload(upload(BACK, OWNER, type, DocumentSide.BACK).incomingObjectKey());
        order.verify(crypto).protect(command.number(), new DocumentProtectionContext(OWNER, type, saved.code()));
        order.verify(writes).insert(saved); order.verify(reads).findByCodeAndOwner(saved.code(), OWNER);
        verify(storage, never()).createUpload(); verify(storage, never()).createView(anyString());
        verify(crypto, never()).reveal(any(), any());
    }

    /** BR-130: chặn ngày cấp tương lai trước đọc AN, chốt ảnh hoặc bảo vệ số. */
    @Test void rejectsFutureIssueDateBeforeStorage() {
        var command = new SubmitDocumentCommand(DocumentType.DRIVER_LICENSE, new DocumentNumber("local-license"),
                null, null, LocalDate.of(2035, 1, 1), "B", false, FRONT, BACK, LocalDate.of(2026, 10, 27));
        var failure = assertThrows(DomainException.class, () -> service.submit(command));
        assertEquals(ErrorCode.INVALID_REQUEST, failure.errorCode());
        verifyNoInteractions(uploads, storage, crypto, writes, reads, codes);
    }

    /** Ngày nộp theo giờ Việt Nam; 17:00 UTC đã thuộc ngày kế tiếp dù Clock dùng UTC. */
    @Test void acceptsIssueDateAtVietnamMidnight() {
        configureUploads(DocumentType.DRIVER_LICENSE);
        var at = Instant.parse("2026-10-26T17:00:00Z");
        var localService = new DocumentSubmissionCommandService(callers, accounts, uploads, storage, crypto,
                writes, reads, codes, Clock.fixed(at, ZoneOffset.UTC));
        var command = new SubmitDocumentCommand(DocumentType.DRIVER_LICENSE, new DocumentNumber("local-license"),
                null, null, LocalDate.of(2035, 1, 1), "B", false, FRONT, BACK, LocalDate.of(2026, 10, 27));
        assertEquals(command.issuedOn(), localService.submit(command).issuedOn());
        assertEquals(at, attempts.getFirst().submittedAt());
    }

    /** Nhân viên có thêm CUSTOMER vẫn nộp hồ sơ của chính mình theo BR-809, không kế thừa quyền admin. */
    @Test void allowsCustomerWithStaffRole() {
        when(callers.current()).thenReturn(new CallerRef(OWNER, Set.of("CUSTOMER", "ADMIN"), null));
        assertEquals(DocumentSubmissionStatus.PENDING, service.submit(command(DocumentType.IDENTITY_CARD)).status());
    }

    /** Mọi vai không có CUSTOMER bị chặn trước khi tra dữ liệu hoặc chạm storage. */
    @ParameterizedTest @EnumSource(value = AccountRole.class, names = "CUSTOMER", mode = EnumSource.Mode.EXCLUDE)
    void rejectsNonCustomer(AccountRole role) {
        when(callers.current()).thenReturn(new CallerRef(OWNER, Set.of(role.name()), null));
        assertFailure(ErrorCode.ACCESS_DENIED, DomainException.Category.FORBIDDEN);
        verifyNoInteractions(accounts, uploads, storage, crypto, writes, reads, codes);
    }

    /** Ngoài HTTP giữ lỗi rõ của CallerDirectory, không tạo danh tính giả. */
    @Test void rejectsMissingCaller() {
        var failure = new IllegalStateException("CallerDirectory requires an active HTTP request.");
        when(callers.current()).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.submit(command(DocumentType.IDENTITY_CARD))));
        verifyNoInteractions(accounts, uploads, storage, crypto, writes, reads);
    }

    /** Tài khoản thiếu/tra nhầm hoặc chưa consent không được nộp; không tự cập nhật consent. */
    @Test void requiresCorrectAccountAndConsent() {
        when(accounts.findByCode(OWNER)).thenReturn(Optional.empty());
        assertFailure(ErrorCode.ACCOUNT_NOT_FOUND, DomainException.Category.NOT_FOUND);
        when(accounts.findByCode(OWNER)).thenReturn(Optional.of(account("TK-OTHER1", true)));
        assertThrows(IllegalStateException.class, () -> service.submit(command(DocumentType.IDENTITY_CARD)));
        when(accounts.findByCode(OWNER)).thenReturn(Optional.of(account(OWNER, false)));
        assertFailure(ErrorCode.CONSENT_REQUIRED, DomainException.Category.FORBIDDEN);
        verifyNoInteractions(uploads, storage, crypto, writes, reads);
    }

    /** AN thiếu hoặc khác chủ cùng trả 404; kể cả mặt sau sai vẫn chưa chốt mặt trước. */
    @ParameterizedTest @ValueSource(strings = {FRONT, BACK})
    void rejectsUnavailableUploadBeforeSealing(String code) {
        when(uploads.findByCodeAndOwner(code, OWNER)).thenReturn(Optional.empty());
        assertFailure(ErrorCode.DOCUMENT_UPLOAD_NOT_FOUND, DomainException.Category.NOT_FOUND);
        verifyNoInteractions(storage, crypto, writes, reads);
    }

    /** Nếu adapter trả nhầm chủ/mã thì fail-closed, không dùng key trong row nhầm đó. */
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void rejectsInconsistentUploadLookup(boolean wrongOwner) {
        when(uploads.findByCodeAndOwner(BACK, OWNER)).thenReturn(Optional.of(upload(
                wrongOwner ? BACK : "AN-OTHER1", wrongOwner ? "TK-OTHER1" : OWNER,
                DocumentType.IDENTITY_CARD, DocumentSide.BACK)));
        assertThrows(IllegalStateException.class, () -> service.submit(command(DocumentType.IDENTITY_CARD)));
        verifyNoInteractions(storage, crypto, writes, reads);
    }

    /** Không tráo loại hoặc mặt đã gắn AN từ lúc cấp URL; cả hai vị trí đều được kiểm. */
    @ParameterizedTest @ValueSource(strings = {"front-type", "back-type", "front-side", "back-side"})
    void rejectsWrongTypeOrSide(String variant) {
        boolean front = variant.startsWith("front");
        String code = front ? FRONT : BACK;
        DocumentSide side = front ? DocumentSide.FRONT : DocumentSide.BACK;
        when(uploads.findByCodeAndOwner(code, OWNER)).thenReturn(Optional.of(upload(code, OWNER,
                variant.endsWith("type") ? DocumentType.DRIVER_LICENSE : DocumentType.IDENTITY_CARD,
                variant.endsWith("side") ? (front ? DocumentSide.BACK : DocumentSide.FRONT) : side)));
        assertFailure(ErrorCode.INVALID_REQUEST, DomainException.Category.INVALID_INPUT);
        verifyNoInteractions(storage, crypto, writes, reads);
    }

    /** URL PUT hết hạn không làm mất quyền nộp ảnh đã tải; không tự áp điều kiện thuê lúc tự khai. */
    @Test void acceptsUploadedImagesAfterPutDeadlineAndExpiredDocument() {
        var command = new SubmitDocumentCommand(DocumentType.IDENTITY_CARD, new DocumentNumber("000000000000"),
                "LOCAL FAKE NAME", LocalDate.of(1990, 1, 1), LocalDate.of(2020, 1, 1), null, null, FRONT, BACK, null);
        assertTrue(upload(FRONT, OWNER, DocumentType.IDENTITY_CARD, DocumentSide.FRONT).expiresAt().isBefore(NOW));
        assertEquals(command.expiresOn(), service.submit(command).expiresOn());
    }

    /** Trùng GT chỉ đổi AAD/phong bì; giữ đúng ảnh đã chốt và thời điểm nộp của lần đầu. */
    @Test void retriesCodeWithNewAadButSameSealedImages() {
        when(codes.generate("GT")).thenReturn("GT-FIRST1", "GT-NEXT02");
        doAnswer(invocation -> {
            attempts.add(invocation.getArgument(0)); return attempts.size() == 2;
        }).when(writes).insert(any());
        var command = command(DocumentType.IDENTITY_CARD);
        assertEquals("GT-NEXT02", service.submit(command).code());
        var first = attempts.getFirst(); var second = attempts.getLast();
        assertEquals(first.frontImage(), second.frontImage()); assertEquals(first.backImage(), second.backImage());
        assertEquals(first.submittedAt(), second.submittedAt());
        assertNotEquals(first.number(), second.number());
        verify(crypto).protect(command.number(), new DocumentProtectionContext(OWNER, command.documentType(), "GT-FIRST1"));
        verify(crypto).protect(command.number(), new DocumentProtectionContext(OWNER, command.documentType(), "GT-NEXT02"));
        verify(storage, times(2)).sealUpload(anyString());
        verify(reads, never()).findByCodeAndOwner("GT-FIRST1", OWNER);
    }

    /** Năm mã đều trùng thì dừng, không đọc hoặc trả hồ sơ chưa được ghi. */
    @Test void stopsAfterFiveCodeCollisions() {
        doReturn(false).when(writes).insert(any());
        assertThrows(IllegalStateException.class, () -> service.submit(command(DocumentType.IDENTITY_CARD)));
        verify(codes, times(5)).generate("GT"); verify(crypto, times(5)).protect(any(), any());
        verify(writes, times(5)).insert(any()); verify(storage, times(2)).sealUpload(anyString());
        verifyNoInteractions(reads);
    }

    /** Lỗi dùng lại AN phải giữ 409, không bị biến thành nhánh thử lại mã GT. */
    @Test void doesNotRetryUsedUpload() {
        doThrow(DomainException.conflict(ErrorCode.DOCUMENT_UPLOAD_ALREADY_USED)).when(writes).insert(any());
        assertFailure(ErrorCode.DOCUMENT_UPLOAD_ALREADY_USED, DomainException.Category.CONFLICT);
        verify(writes, times(1)).insert(any()); verify(codes, times(1)).generate("GT"); verifyNoInteractions(reads);
    }

    /** Ba lỗi ảnh đã duyệt và lỗi kỹ thuật storage giữ nguyên; chưa ghi kể cả lỗi ở mặt sau. */
    @ParameterizedTest @EnumSource(value = ErrorCode.class, names = {
            "DOCUMENT_UPLOAD_NOT_READY", "DOCUMENT_IMAGE_TOO_LARGE", "DOCUMENT_IMAGE_INVALID"})
    void propagatesImageRejectionsBeforeWriting(ErrorCode code) {
        var failure = DomainException.ruleViolation(code);
        when(storage.sealUpload(upload(BACK, OWNER, DocumentType.IDENTITY_CARD, DocumentSide.BACK).incomingObjectKey()))
                .thenThrow(failure);
        assertSame(failure, assertThrows(DomainException.class, () -> service.submit(command(DocumentType.IDENTITY_CARD))));
        verifyNoInteractions(crypto, writes, reads, codes);
    }

    /** Không nuốt lỗi storage/crypto/persistence thành mã trùng; message của service không chèn dữ liệu hồ sơ. */
    @ParameterizedTest @ValueSource(strings = {"storage", "crypto", "write"})
    void propagatesTechnicalFailureWithoutRetry(String stage) {
        var failure = new IllegalStateException("Safe dependency failure.");
        switch (stage) {
            case "storage" -> doThrow(failure).when(storage).sealUpload(anyString());
            case "crypto" -> doThrow(failure).when(crypto).protect(any(), any());
            case "write" -> doThrow(failure).when(writes).insert(any());
            default -> fail("Unknown test stage.");
        }
        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.submit(command(DocumentType.IDENTITY_CARD))));
        verify(writes, atMostOnce()).insert(any()); verifyNoInteractions(reads);
    }

    /** Không dựng view thành công từ command nếu đọc lại thiếu hoặc khác mã/loại/trạng thái/hạn/mốc. */
    @ParameterizedTest @ValueSource(strings = {"missing", "code", "type", "status", "expiry", "time", "issuedOn"})
    void rejectsMissingOrInconsistentReload(String field) {
        when(reads.findByCodeAndOwner(anyString(), eq(OWNER))).thenAnswer(invocation -> {
            var row = detail(attempts.getLast());
            if (field.equals("missing")) return Optional.empty();
            return Optional.of(new DocumentSubmissionDetail(field.equals("code") ? "GT-OTHER1" : row.code(),
                    field.equals("type") ? DocumentType.DRIVER_LICENSE : row.documentType(),
                    field.equals("status") ? DocumentSubmissionStatus.VERIFIED : row.status(),
                    field.equals("expiry") ? row.expiresOn().plusDays(1) : row.expiresOn(),
                    field.equals("time") ? row.submittedAt().plusSeconds(1) : row.submittedAt(), field.equals("issuedOn") ? LocalDate.of(2025, 1, 1) : row.issuedOn()));
        });
        assertThrows(IllegalStateException.class, () -> service.submit(command(DocumentType.IDENTITY_CARD)));
        verify(writes, times(1)).insert(any());
    }

    /** Metadata thiếu hoặc key không sealed không được truyền tới port ghi. */
    @Test void rejectsInvalidStorageResult() {
        when(storage.sealUpload(anyString())).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> service.submit(command(DocumentType.IDENTITY_CARD)));
        when(storage.sealUpload(anyString())).thenReturn(new StoredImageRef("incoming/private", "image/png", 100, 10, 10));
        assertThrows(IllegalArgumentException.class, () -> service.submit(command(DocumentType.IDENTITY_CARD)));
        verifyNoInteractions(crypto, writes, reads);
    }

    /** Kiểm cả mã và nhóm lỗi, không chỉ kiểm có exception. */
    private void assertFailure(ErrorCode code, DomainException.Category category) {
        var failure = assertThrows(DomainException.class, () -> service.submit(command(DocumentType.IDENTITY_CARD)));
        assertEquals(code, failure.errorCode()); assertEquals(category, failure.category());
    }

    /** Hai AN cùng chủ/loại nhưng đúng mặt riêng, URL PUT đã hết hạn vẫn có ảnh. */
    private void configureUploads(DocumentType type) {
        when(uploads.findByCodeAndOwner(FRONT, OWNER)).thenReturn(Optional.of(upload(FRONT, OWNER, type, DocumentSide.FRONT)));
        when(uploads.findByCodeAndOwner(BACK, OWNER)).thenReturn(Optional.of(upload(BACK, OWNER, type, DocumentSide.BACK)));
    }

    /** Key giả chỉ dùng cho mock; không gọi MinIO hoặc giữ URL thật. */
    private static DocumentUpload upload(String code, String owner, DocumentType type, DocumentSide side) {
        String suffix = side == DocumentSide.FRONT ? "11111111-1111-1111-1111-111111111111" : "22222222-2222-2222-2222-222222222222";
        return new DocumentUpload(code, owner, type, side, "incoming/" + suffix, NOW.minusSeconds(600), NOW.minusSeconds(300));
    }

    /** Command của hai loại không mang chủ, trạng thái hoặc object key. */
    private static SubmitDocumentCommand command(DocumentType type) {
        boolean card = type == DocumentType.IDENTITY_CARD;
        return new SubmitDocumentCommand(type, new DocumentNumber("000000000000"), card ? "LOCAL FAKE NAME" : null,
                card ? LocalDate.of(1990, 1, 1) : null, LocalDate.of(2035, 1, 1), card ? null : "B",
                card ? null : false, FRONT, BACK, card ? null : LocalDate.of(2025, 1, 1));
    }

    /** Tài khoản local giả có/không consent để kiểm cổng application độc lập HTTP. */
    private static Account account(String code, boolean consent) {
        return Account.restore(code, "fake-subject", new VietnamMobileNumber("+84912345678"),
                consent ? new TermsConsent("test-v1", NOW.minusSeconds(10)) : null);
    }

    /** View mock chỉ để điều khiển reload; adapter thật được kiểm riêng với PostgreSQL. */
    private static DocumentSubmissionDetail detail(DocumentSubmission value) {
        return new DocumentSubmissionDetail(value.code(), value.documentType(), DocumentSubmissionStatus.PENDING,
                value.expiresOn(), value.submittedAt(), value.issuedOn());
    }
}
