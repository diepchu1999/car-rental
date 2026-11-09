package com.carrental.identity.adapter.out.persistence;

import com.carrental.PostgresTestConfiguration;
import com.carrental.identity.api.CallerDirectory;
import com.carrental.identity.api.CallerRef;
import com.carrental.identity.application.command.SubmitDocumentCommand;
import com.carrental.identity.application.port.in.SubmitDocumentUseCase;
import com.carrental.identity.application.port.out.ReadDocumentSubmissionPort;
import com.carrental.identity.domain.*;
import com.carrental.shared.code.BusinessCodeGenerator;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.shared.sql.SensitiveSql;
import com.carrental.storage.api.DocumentStorageDirectory;
import com.carrental.storage.api.StoredImageRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Service proxy/crypto/JDBC/PostgreSQL thật; chỉ giả caller và storage ở biên, không có transaction của test. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class DocumentSubmissionServiceIntegrationTest extends DocumentDatabaseTestSupport {
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final Instant NOW = Instant.parse("2026-10-26T03:00:00.123456789Z");
    @Autowired SubmitDocumentUseCase useCase;
    @MockitoSpyBean ReadDocumentSubmissionPort reads;
    @MockitoBean CallerDirectory callers;
    @MockitoBean DocumentStorageDirectory storage;
    @MockitoBean BusinessCodeGenerator codes;
    @MockitoBean Clock clock;
    private long ownerId;
    private String owner;

    /** Fixture được commit trước lời gọi use case; caller và bytes không lấy từ hệ thống local. */
    @BeforeEach void setUp() {
        var account = accountParameters().addValue("version", "test-v1").addValue("consentedAt", AT);
        ownerId = insertAccount(account);
        owner = (String) account.getValue("code");
        when(callers.current()).thenReturn(new CallerRef(owner, Set.of("CUSTOMER"), null));
        when(clock.instant()).thenReturn(NOW);
        when(codes.generate("GT")).thenAnswer(ignored -> nextCode());
        when(storage.sealUpload(anyString())).thenAnswer(ignored ->
                new StoredImageRef("sealed/" + UUID.randomUUID(), "image/png", 100, 10, 10));
    }

    /** Hai loại đều commit đủ hai mặt, SQL không chứa số rõ, phong bì giải mã đúng chủ/loại/GT. */
    @ParameterizedTest @EnumSource(DocumentType.class)
    void commitsAndReloadsBothDocumentTypes(DocumentType type) {
        var command = command(type);
        var result = useCase.submit(command);
        var row = rows(result.code()).getFirst();
        assertEquals(DocumentSubmissionStatus.PENDING, result.status());
        assertEquals(type, result.documentType());
        assertEquals(command.expiresOn(), result.expiresOn());
        assertEquals(NOW.truncatedTo(java.time.temporal.ChronoUnit.MICROS), result.submittedAt());
        assertEquals(result.code(), row.get("latest_code")); assertNull(row.get("active_code"));
        assertEquals(2L, row.get("image_count"));
        var protectedNumber = new ProtectedDocumentNumber((Integer) row.get("number_key_version"),
                (String) row.get("number_nonce"), (String) row.get("number_ciphertext"), (String) row.get("number_hash"));
        assertNotEquals(FAKE_NUMBER, protectedNumber.ciphertextBase64());
        assertEquals(command.number(), crypto.reveal(protectedNumber, new DocumentProtectionContext(owner, type, result.code())));
        assertEquals(Optional.of(result), reads.findByCodeAndOwner(result.code(), owner));
        verify(storage, times(2)).sealUpload(anyString());
    }

    /** SQL đọc chặn cả chủ khác và mã không tồn tại; test này đỏ nếu bỏ điều kiện a.code ở SQL main. */
    @Test void doesNotReadAnotherCustomersSubmission() {
        var result = useCase.submit(command(DocumentType.IDENTITY_CARD));
        String other = accountCode(newAccount());
        assertTrue(reads.findByCodeAndOwner(result.code(), other).isEmpty(), "Another owner must not read the submission.");
        assertTrue(reads.findByCodeAndOwner("GT-NONE00", owner).isEmpty());
        assertTrue(reads.findByCodeAndOwner(result.code(), owner).isPresent());
    }

    /** Khách dùng AN của chủ khác nhận đúng 404 trước mọi thao tác đọc/chốt storage. */
    @Test void rejectsAnotherCustomersBackUploadBeforeStorage() {
        var command = command(DocumentType.IDENTITY_CARD);
        var foreign = upload(newAccount(), "IDENTITY_CARD", "BACK");
        SensitiveSql.execute(() -> insert("upload", foreign));
        var invalid = new SubmitDocumentCommand(command.documentType(), command.number(), command.fullName(),
                command.birthDate(), command.expiresOn(), null, null,
                command.frontUploadCode(), (String) foreign.getValue("code"), command.issuedOn());
        var failure = assertThrows(DomainException.class, () -> useCase.submit(invalid));
        assertEquals(ErrorCode.DOCUMENT_UPLOAD_NOT_FOUND, failure.errorCode());
        assertEquals(DomainException.Category.NOT_FOUND, failure.category());
        verifyNoInteractions(storage, codes);
    }

    /** Mất reload sau ghi phải rollback toàn use case, gồm ảnh và thay PENDING; cùng AN/GT thử lại được. */
    @Test void reloadFailureRollsBackSubmissionImagesAndHead() {
        String previous = useCase.submit(command(DocumentType.IDENTITY_CARD)).code();
        var command = command(DocumentType.IDENTITY_CARD);
        String failedCode = nextCode();
        when(codes.generate("GT")).thenReturn(failedCode);
        doReturn(Optional.empty()).when(reads).findByCodeAndOwner(failedCode, owner);
        assertThrows(IllegalStateException.class, () -> useCase.submit(command));
        assertTrue(rows(failedCode).isEmpty());
        var old = rows(previous).getFirst();
        assertEquals("PENDING", old.get("status")); assertEquals(previous, old.get("latest_code"));
        assertEquals(2L, old.get("image_count"));
        // Khôi phục spy đọc thật; transaction đã rollback nên GT và hai AN không bị chiếm.
        reset(reads);
        assertEquals(failedCode, useCase.submit(command).code());
        assertEquals("SUPERSEDED", rows(previous).getFirst().get("status"));
        assertEquals(2L, rows(failedCode).getFirst().get("image_count"));
    }

    /** GT đụng bản đã có được thử lại trong cùng transaction, ảnh không chốt lại và AAD dùng GT thắng. */
    @Test void retriesActualCodeCollisionWithCorrectEncryptionContext() {
        String existing = useCase.submit(command(DocumentType.IDENTITY_CARD)).code();
        var command = command(DocumentType.IDENTITY_CARD);
        String next = nextCode();
        when(codes.generate("GT")).thenReturn(existing, next);
        clearInvocations(storage);
        assertEquals(next, useCase.submit(command).code());
        verify(storage, times(2)).sealUpload(anyString());
        var row = rows(next).getFirst();
        var envelope = new ProtectedDocumentNumber((Integer) row.get("number_key_version"),
                (String) row.get("number_nonce"), (String) row.get("number_ciphertext"), (String) row.get("number_hash"));
        assertEquals(command.number(), crypto.reveal(envelope, new DocumentProtectionContext(owner, command.documentType(), next)));
        assertThrows(IllegalStateException.class, () -> crypto.reveal(envelope,
                new DocumentProtectionContext(owner, command.documentType(), existing)));
        assertEquals("SUPERSEDED", rows(existing).getFirst().get("status"));
    }

    /** UNIQUE AN thật trả 409, giữ bản đã nộp và không biến thành vòng thử mã. */
    @Test void rejectsUsedUploadWithoutRetryAndKeepsPreviousSubmission() {
        var command = command(DocumentType.IDENTITY_CARD);
        String existing = useCase.submit(command).code();
        clearInvocations(codes);
        var failure = assertThrows(DomainException.class, () -> useCase.submit(command));
        assertEquals(ErrorCode.DOCUMENT_UPLOAD_ALREADY_USED, failure.errorCode());
        assertEquals(DomainException.Category.CONFLICT, failure.category());
        verify(codes, times(1)).generate("GT");
        var row = rows(existing).getFirst();
        assertEquals("PENDING", row.get("status")); assertEquals(existing, row.get("latest_code"));
        assertEquals(2L, row.get("image_count"));
    }

    /** Nộp hai phiên bản qua service chỉ thay pending; bản VERIFIED cũ và con trỏ active vẫn nguyên. */
    @Test void preservesVerifiedVersionWhileReplacingPending() {
        var verified = submission(ownerId, "IDENTITY_CARD", "VERIFIED");
        long active = SensitiveSql.execute(() -> insert("submission", verified));
        SensitiveSql.execute(() -> insert("head", head(ownerId, "IDENTITY_CARD", active, active)));
        String first = useCase.submit(command(DocumentType.IDENTITY_CARD)).code();
        String second = useCase.submit(command(DocumentType.IDENTITY_CARD)).code();
        assertEquals("SUPERSEDED", rows(first).getFirst().get("status"));
        var last = rows(second).getFirst();
        assertEquals("PENDING", last.get("status")); assertEquals(second, last.get("latest_code"));
        assertEquals(verified.getValue("code"), last.get("active_code"));
        assertEquals("VERIFIED", rows((String) verified.getValue("code")).getFirst().get("status"));
    }

    /** Tạo AN thật đúng chủ/loại/mặt; hạn PUT cũ không phải hạn nộp của khách. */
    private SubmitDocumentCommand command(DocumentType type) {
        var front = upload(ownerId, type.name(), "FRONT");
        var back = upload(ownerId, type.name(), "BACK");
        SensitiveSql.execute(() -> insert("upload", front));
        SensitiveSql.execute(() -> insert("upload", back));
        boolean card = type == DocumentType.IDENTITY_CARD;
        return new SubmitDocumentCommand(type, new DocumentNumber(FAKE_NUMBER), card ? "LOCAL FAKE NAME" : null,
                card ? LocalDate.of(1990, 1, 1) : null, LocalDate.of(2035, 1, 1), card ? null : "B",
                card ? null : false, (String) front.getValue("code"), (String) back.getValue("code"), card ? null : LocalDate.of(2025, 1, 1));
    }

    /** Đọc bằng SQL kiểm chứng độc lập sau transaction service, không phụ thuộc spy reload. */
    private List<Map<String, Object>> rows(String code) {
        return SensitiveSql.execute(() -> jdbc.queryForList(documentSql("read_submission_persistence"),
                Map.of("code", code, "accountCode", owner)));
    }

    /** Mã fixture tách khỏi các fixture D/P ở lớp khác, không sửa bộ sinh mã production. */
    private static String nextCode() { return "GT-S%05d".formatted(SEQUENCE.incrementAndGet()); }
}
