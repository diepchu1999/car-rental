package com.carrental.identity.application.service;

import com.carrental.identity.api.CallerDirectory;
import com.carrental.identity.api.CallerRef;
import com.carrental.identity.application.command.CreateDocumentUploadCommand;
import com.carrental.identity.application.port.out.ReadAccountPort;
import com.carrental.identity.application.port.out.ReadDocumentUploadPort;
import com.carrental.identity.application.port.out.WriteDocumentUploadPort;
import com.carrental.identity.domain.*;
import com.carrental.shared.code.BusinessCodeGenerator;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.storage.api.DocumentStorageDirectory;
import com.carrental.storage.api.UploadRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** BR-120/130: kiểm quyền trước ký, chủ tin cậy, thử mã hữu hạn và giữ mốc storage cấp. */
class DocumentUploadCommandServiceTest {
    private static final String OWNER = "TK-SELF01";
    private static final Instant NOW = Instant.parse("2026-10-23T03:00:00.123456789Z");
    private static final UploadRef SIGNED = new UploadRef("incoming/11111111-1111-1111-1111-111111111111",
            "http://localhost/fake-upload?fake-signature=private", NOW.plusSeconds(47));
    private final CallerDirectory callers = mock(CallerDirectory.class);
    private final ReadAccountPort accounts = mock(ReadAccountPort.class);
    private final DocumentStorageDirectory storage = mock(DocumentStorageDirectory.class);
    private final WriteDocumentUploadPort writes = mock(WriteDocumentUploadPort.class);
    private final ReadDocumentUploadPort reads = mock(ReadDocumentUploadPort.class);
    private final BusinessCodeGenerator codes = mock(BusinessCodeGenerator.class);
    private final DocumentUploadCommandService service = new DocumentUploadCommandService(callers, accounts, storage,
            writes, reads, codes, Clock.fixed(NOW, ZoneOffset.UTC));
    private DocumentUpload stored;

    /** Cổng giả không thay cho PostgreSQL test; chỉ điều khiển kết quả từng nhánh điều phối. */
    @BeforeEach void setUp() {
        when(callers.current()).thenReturn(new CallerRef(OWNER, Set.of("CUSTOMER"), null));
        when(accounts.findByCode(OWNER)).thenReturn(Optional.of(account(OWNER, true)));
        when(storage.createUpload()).thenReturn(SIGNED);
        when(codes.generate("AN")).thenReturn("AN-ONE001");
        when(writes.insert(any())).thenAnswer(invocation -> { stored = invocation.getArgument(0); return true; });
        when(reads.findByCodeAndOwner(anyString(), eq(OWNER))).thenAnswer(invocation -> Optional.ofNullable(stored));
    }

    /** Dùng chủ caller và thời hạn storage, reload trước trả; URL không bị in qua toString. */
    @Test void createsForAuthenticatedOwnerAndPreservesStorageDeadline() {
        var result = service.create(command());
        assertEquals(OWNER, stored.accountCode());
        assertEquals(DocumentType.IDENTITY_CARD, stored.documentType());
        assertEquals(DocumentSide.FRONT, stored.side());
        assertEquals(NOW.truncatedTo(ChronoUnit.MICROS), stored.createdAt());
        assertEquals(SIGNED.expiresAt().truncatedTo(ChronoUnit.MICROS), result.expiresAt());
        assertEquals(SIGNED.url(), result.uploadUrl());
        assertFalse(result.toString().contains("private"));
        var order = inOrder(callers, accounts, storage, writes, reads);
        order.verify(callers).current(); order.verify(accounts).findByCode(OWNER);
        order.verify(storage).createUpload(); order.verify(writes).insert(stored);
        order.verify(reads).findByCodeAndOwner(stored.code(), OWNER);
    }

    /** Mọi vai không phải khách bị chặn trước khi tra tài khoản, ký hoặc ghi lượt tải. */
    @ParameterizedTest @EnumSource(value = AccountRole.class, names = "CUSTOMER", mode = EnumSource.Mode.EXCLUDE)
    void rejectsNonCustomerBeforeAccessingStorage(AccountRole role) {
        when(callers.current()).thenReturn(new CallerRef(OWNER, Set.of(role.name()), null));
        assertEquals(ErrorCode.ACCESS_DENIED, assertThrows(DomainException.class,
                () -> service.create(command())).errorCode());
        verifyNoInteractions(accounts, storage, codes, writes, reads);
    }

    /** Ngoài HTTP không dựng khách mặc định; giữ lỗi rõ của CallerDirectory. */
    @Test void rejectsMissingHttpCaller() {
        var failure = new IllegalStateException("CallerDirectory requires an active HTTP request.");
        when(callers.current()).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.create(command())));
        verifyNoInteractions(accounts, storage, writes, reads);
    }

    /** Thiếu consent vẫn bị chặn ở application, không chỉ dựa vào filter HTTP. */
    @Test void requiresPersistedConsent() {
        when(accounts.findByCode(OWNER)).thenReturn(Optional.of(account(OWNER, false)));
        assertEquals(ErrorCode.CONSENT_REQUIRED, assertThrows(DomainException.class,
                () -> service.create(command())).errorCode());
        verifyNoInteractions(storage, writes, reads);
    }

    /** Tài khoản không tồn tại không được tạo lượt tải mồ côi. */
    @Test void rejectsMissingAccount() {
        when(accounts.findByCode(OWNER)).thenReturn(Optional.empty());
        assertEquals(ErrorCode.ACCOUNT_NOT_FOUND, assertThrows(DomainException.class,
                () -> service.create(command())).errorCode());
        verifyNoInteractions(storage, writes, reads);
    }

    /** Adapter trả nhầm tài khoản không được dùng làm chủ. */
    @Test void rejectsInconsistentAccount() {
        when(accounts.findByCode(OWNER)).thenReturn(Optional.of(account("TK-OTHER1", true)));
        assertThrows(IllegalStateException.class, () -> service.create(command()));
        verifyNoInteractions(storage, writes, reads);
    }

    /** Một lần trùng mã chỉ đổi AN, không ký lại key/hạn hoặc đọc nhầm lượt đã tồn tại. */
    @Test void retriesCodeCollisionWithoutRenewingUpload() {
        when(codes.generate("AN")).thenReturn("AN-ONE001", "AN-TWO002");
        when(writes.insert(any())).thenAnswer(invocation -> {
            stored = invocation.getArgument(0);
            return stored.code().equals("AN-TWO002");
        });
        assertEquals("AN-TWO002", service.create(command()).code());
        verify(storage, times(1)).createUpload();
        verify(writes, times(2)).insert(any());
        verify(reads, times(1)).findByCodeAndOwner("AN-TWO002", OWNER);
        assertEquals(SIGNED.objectKey(), stored.incomingObjectKey());
    }

    /** Năm lần trùng liên tiếp dừng hữu hạn, không trả URL cho lượt tải chưa lưu. */
    @Test void stopsAfterFiveCodeCollisions() {
        when(writes.insert(any())).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> service.create(command()));
        verify(writes, times(5)).insert(any()); verify(codes, times(5)).generate("AN");
        verify(storage, times(1)).createUpload(); verifyNoInteractions(reads);
    }

    /** Lỗi ghi không bị bắt thành trùng mã và thử lại ngoài ý muốn. */
    @Test void doesNotRetryStorageFailures() {
        var failure = new IllegalStateException("Safe persistence failure.");
        when(writes.insert(any())).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.create(command())));
        verify(writes, times(1)).insert(any()); verifyNoInteractions(reads);
    }

    /** Ký lỗi thì không ghi lượt tải; không giữ URL hoặc key làm message của service. */
    @Test void stopsWhenSigningFails() {
        when(storage.createUpload()).thenThrow(new IllegalStateException("Safe storage failure."));
        assertThrows(IllegalStateException.class, () -> service.create(command()));
        verifyNoInteractions(writes, reads);
    }

    /** Reload thiếu hoặc sai nội dung phải lỗi để transaction ngoài rollback. */
    @Test void rejectsMissingOrInconsistentReload() {
        when(reads.findByCodeAndOwner(anyString(), eq(OWNER))).thenReturn(Optional.empty());
        assertThrows(IllegalStateException.class, () -> service.create(command()));
        when(reads.findByCodeAndOwner(anyString(), eq(OWNER))).thenAnswer(invocation -> Optional.of(new DocumentUpload(
                stored.code(), stored.accountCode(), stored.documentType(), DocumentSide.BACK,
                stored.incomingObjectKey(), stored.createdAt(), stored.expiresAt())));
        assertThrows(IllegalStateException.class, () -> service.create(command()));
    }

    /** Tài khoản giả có/không bằng chứng điều khoản; không truy cập Keycloak local. */
    private static Account account(String code, boolean consent) {
        return Account.restore(code, "fake-subject", new VietnamMobileNumber("+84912345678"),
                consent ? new TermsConsent("test-v1", NOW.minusSeconds(10)) : null);
    }

    /** Command không mang accountCode, key hoặc TTL. */
    private static CreateDocumentUploadCommand command() {
        return CreateDocumentUploadCommand.from("IDENTITY_CARD", "FRONT");
    }
}
