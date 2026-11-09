package com.carrental.identity.adapter.out.persistence;

import com.carrental.PostgresTestConfiguration;
import com.carrental.audit.application.port.out.WriteAuditEventPort;
import com.carrental.identity.api.CallerDirectory;
import com.carrental.identity.api.CallerRef;
import com.carrental.identity.application.command.OpenDocumentViewSessionCommand;
import com.carrental.identity.application.port.in.OpenDocumentViewSessionUseCase;
import com.carrental.identity.application.port.out.DocumentNumberCryptoPort;
import com.carrental.identity.domain.DocumentSide;
import com.carrental.identity.domain.DocumentType;
import com.carrental.storage.api.DocumentStorageDirectory;
import com.carrental.storage.api.SignedImageRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Use case/proxy/crypto/audit/PostgreSQL thật; chỉ giả caller và storage ở biên.
 * Không bọc test trong transaction: quan sát commit bằng connection độc lập trước khi ký URL.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DocumentViewSessionServiceIntegrationTest extends DocumentDatabaseTestSupport {
    @Autowired OpenDocumentViewSessionUseCase useCase;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactions;
    @Autowired Clock clock;
    @MockitoBean CallerDirectory callers;
    @MockitoBean DocumentStorageDirectory storage;
    @MockitoSpyBean DocumentNumberCryptoPort numbers;
    @MockitoSpyBean WriteAuditEventPort auditWrites;
    private long owner;
    private String ownerCode;
    private String actorCode;
    private String requestId;

    /** Tài khoản được commit trước, đều là dữ liệu trong container tạm, không lấy user local. */
    @BeforeEach void setUp() {
        owner = newAccount(); ownerCode = accountCode(owner); actorCode = accountCode(newAccount());
        requestId = UUID.randomUUID().toString();
        when(callers.current()).thenReturn(new CallerRef(actorCode, Set.of("DOCUMENT_VERIFIER"), null));
    }

    /** Hai lần cùng requestId vẫn đúng hai audit; cả giải mã và ký chỉ bắt đầu khi connection khác thấy commit. */
    @ParameterizedTest @EnumSource(DocumentType.class)
    void commitsAuditBeforeDecryptionAndSigningForEveryOpen(DocumentType type) throws Exception {
        var command = prepare(type);
        var expected = new AtomicInteger(1);
        doAnswer(invocation -> {
            assertCommitted(command, expected.get());
            return invocation.callRealMethod();
        }).when(numbers).reveal(any(), any());
        when(storage.createView(anyString())).thenAnswer(invocation -> {
            assertCommitted(command, expected.get());
            return link();
        });
        for (int opened = 1; opened <= 2; opened++) {
            expected.set(opened);
            var result = useCase.open(command);
            assertEquals(fakeNumber(ownerCode), result.number().value());
            assertEquals(ownerCode, result.accountCode()); assertEquals(type, result.submission().documentType());
            assertEquals("DocumentViewSessionDetail[redacted]", result.toString());
            assertCommitted(command, opened);
        }
        verify(auditWrites, times(2)).insert(any());
        verify(numbers, times(2)).reveal(any(), any()); verify(storage, times(4)).createView(anyString());
    }

    /** INSERT audit đã chạy nhưng commit lỗi thật: rollback event và tuyệt đối không giải mã/ký. */
    @Test void failedAuditCommitPreventsAllSensitiveOutput() throws Exception {
        var command = prepare(DocumentType.IDENTITY_CARD);
        var reachedCommit = new AtomicBoolean();
        var failure = new IllegalStateException("Forced audit commit failure.");
        doAnswer(invocation -> {
            invocation.callRealMethod();
            assertTrue(TransactionSynchronizationManager.isSynchronizationActive());
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                /** Chỉ hỏng commit của transaction audit trong test, không thay adapter thành mock thành công. */
                @Override public void beforeCommit(boolean readOnly) { reachedCommit.set(true); throw failure; }
            });
            return null;
        }).when(auditWrites).insert(any());
        assertSame(failure, assertThrows(IllegalStateException.class, () -> useCase.open(command)));
        assertTrue(reachedCommit.get()); assertTrue(events().isEmpty());
        verify(auditWrites).insert(any()); verify(numbers, never()).reveal(any(), any()); verifyNoInteractions(storage);
    }

    /** Lỗi ký sau audit không xoá dấu vết, không thử lại thành nhiều event cho một lượt mở. */
    @Test void signingFailureKeepsCommittedAudit() throws Exception {
        var command = prepare(DocumentType.DRIVER_LICENSE);
        var failure = new IllegalStateException("Forced storage failure.");
        when(storage.createView(anyString())).thenAnswer(invocation -> { assertCommitted(command, 1); throw failure; });
        assertSame(failure, assertThrows(IllegalStateException.class, () -> useCase.open(command)));
        assertCommitted(command, 1); verify(auditWrites).insert(any()); verify(storage).createView(anyString());
    }

    /** ADMIN có cùng bảo đảm: transaction ngoài rollback vẫn giữ audit đã commit trước trả view. */
    @Test void outerRollbackCannotEraseAdminViewAudit() throws Exception {
        var command = prepare(DocumentType.IDENTITY_CARD);
        when(callers.current()).thenReturn(new CallerRef(actorCode, Set.of("ADMIN"), null));
        when(storage.createView(anyString())).thenAnswer(invocation -> { assertCommitted(command, 1); return link(); });
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertEquals(command.code(), useCase.open(command).submission().code());
            status.setRollbackOnly();
        });
        assertCommitted(command, 1);
    }

    /** Hồ sơ mã hoá bằng adapter thật, đủ hai ảnh; lưu xong rồi mới mở qua proxy Spring. */
    private OpenDocumentViewSessionCommand prepare(DocumentType type) {
        var parameters = submission(owner, type.name(), "PENDING");
        long submissionId = insert("submission", parameters);
        for (DocumentSide side : DocumentSide.values()) {
            long uploadId = insert("upload", upload(owner, type.name(), side.name()));
            insert("image", image(submissionId, uploadId, owner, type.name(), side.name()));
        }
        clearInvocations(numbers, auditWrites);
        return OpenDocumentViewSessionCommand.from((String) parameters.getValue("code"), requestId);
    }

    /** Kết nối trực tiếp từ pool, không dùng connection gắn transaction Spring của bên đang ghi. */
    private List<Map<String, Object>> events() throws Exception {
        try (var connection = dataSource.getConnection()) {
            var independent = new NamedParameterJdbcTemplate(new SingleConnectionDataSource(connection, true));
            return independent.queryForList(sqlLoader.load("sql/audit/read_events.sql"), Map.of("requestId", requestId));
        }
    }

    /** Kiểm đúng ai mở, chủ nào, phiên bản nào và thời điểm, không suy commit từ số lời gọi mock. */
    private void assertCommitted(OpenDocumentViewSessionCommand command, int expected) throws Exception {
        var rows = events(); assertEquals(expected, rows.size(), "Audit must be committed before exposing document data.");
        for (var row : rows) {
            assertEquals(actorCode, row.get("actor_account_code")); assertEquals(ownerCode, row.get("subject_account_code"));
            assertEquals(command.code(), row.get("resource_code")); assertEquals(requestId, row.get("request_id"));
            assertEquals("DOCUMENT_VIEW_OPENED", row.get("action")); assertNotNull(row.get("occurred_at"));
        }
    }

    /** URL giả chỉ kiểm điều phối; kiểm chữ ký/TTL MinIO thật thuộc bộ test storage đã có. */
    private SignedImageRef link() { return new SignedImageRef("https://local.invalid/image?signature=fake", clock.instant().plusSeconds(60)); }
}
