package com.carrental.identity.adapter.out.persistence;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.carrental.PostgresTestConfiguration;
import com.carrental.audit.application.port.out.WriteAuditEventPort;
import com.carrental.identity.api.CallerDirectory;
import com.carrental.identity.api.CallerRef;
import com.carrental.identity.application.port.in.SearchPendingDocumentsUseCase;
import com.carrental.identity.application.port.out.DocumentNumberCryptoPort;
import com.carrental.identity.application.port.out.ReadDocumentSubmissionPort;
import com.carrental.identity.application.query.SearchPendingDocumentsQuery;
import com.carrental.identity.application.view.PendingDocumentListItem;
import com.carrental.identity.domain.DocumentSubmissionStatus;
import com.carrental.identity.domain.DocumentType;
import com.carrental.shared.sql.SensitiveSql;
import com.carrental.storage.application.port.out.DocumentObjectPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** BR-130: truy vấn hàng chờ PostgreSQL thật; service và transaction thật, caller giả ở biên request. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class DocumentQueueReadIntegrationTest extends DocumentDatabaseTestSupport {
    @Autowired SearchPendingDocumentsUseCase useCase;
    @Autowired Environment environment;
    @MockitoBean CallerDirectory callers;
    @MockitoBean DocumentObjectPort objects;
    @MockitoSpyBean ReadDocumentSubmissionPort reads;
    @MockitoSpyBean DocumentNumberCryptoPort numbers;
    @MockitoSpyBean WriteAuditEventPort auditWrites;

    /** Verifier cấp công ty không có branch; fixture dùng account CSDL riêng, không token local. */
    @BeforeEach void setUp() {
        when(callers.current()).thenReturn(new CallerRef("TK-ACTOR1", Set.of("DOCUMENT_VERIFIER"), null));
    }

    /** Hàng chờ chỉ là metadata; không giải mã, đọc/ký ảnh hoặc ghi audit mở giấy tờ. */
    @AfterEach void doesNotOpenSensitiveDocuments() {
        verify(numbers, never()).reveal(any(), any()); verifyNoInteractions(objects, auditWrites);
        assertFalse(SensitiveSql.isActive());
    }

    /** Cả hai loại PENDING được trả, mọi trạng thái khác bị loại ở SQL, không ở bộ lọc Java. */
    @Test void selectsOnlyPendingAcrossOwnersAndTypes() {
        var expected = new ArrayList<PendingDocumentListItem>();
        for (DocumentSubmissionStatus status : DocumentSubmissionStatus.values()) {
            for (DocumentType type : DocumentType.values()) {
                long owner = newAccount(); var parameters = submission(owner, type.name(), status.name());
                insert("submission", parameters);
                if (status == DocumentSubmissionStatus.PENDING) expected.add(item(owner, parameters));
            }
        }
        assertEquals(expected, reads.searchPending(101, 0));
        assertEquals(expected, useCase.search(SearchPendingDocumentsQuery.from(null, null)).items());
    }

    /** Hai mốc cùng nhau sắp theo mã GT chứ không theo ID; thời điểm cũ luôn đứng trước trong danh sách. */
    @Test void ordersBySubmissionTimeThenBusinessCodeAndPaginatesInSql() {
        long ownerA = newAccount(), ownerB = newAccount(), ownerC = newAccount();
        var a = submission(ownerA, "IDENTITY_CARD", "PENDING");
        var b = submission(ownerB, "DRIVER_LICENSE", "PENDING");
        var c = submission(ownerC, "IDENTITY_CARD", "PENDING").addValue("submittedAt", "2026-10-12T03:00:00Z");
        // Chèn B trước A để ID tăng dần cho thứ tự khác mã GT khi hai timestamp bằng nhau.
        insert("submission", b); insert("submission", a); insert("submission", c);
        var expected = List.of(item(ownerC, c), item(ownerA, a), item(ownerB, b));
        assertEquals(expected, reads.searchPending(101, 0));
        assertEquals(expected.subList(1, 2), reads.searchPending(1, 1));
        assertEquals(expected.subList(2, 3), reads.searchPending(2, 2));
        assertTrue(reads.searchPending(1, Long.MAX_VALUE).isEmpty());
    }

    /** Latest PENDING chỉ xuất hiện một lần; bản SUPERSEDED/VERIFIED lịch sử và head active không thêm hàng. */
    @Test void ignoresHistoricalVersionsWithoutOpeningImages() {
        long owner = newAccount();
        insert("submission", submission(owner, "IDENTITY_CARD", "SUPERSEDED"));
        long active = insert("submission", submission(owner, "IDENTITY_CARD", "VERIFIED"));
        var pending = submission(owner, "IDENTITY_CARD", "PENDING");
        long latest = insert("submission", pending);
        insert("head", head(owner, "IDENTITY_CARD", latest, active));
        assertEquals(List.of(item(owner, pending)), reads.searchPending(101, 0));
        // Không tạo ảnh trong fixture: hàng chờ không được JOIN ảnh để vô tình làm biến mất bản nộp.
    }

    /** Nhiều hơn một trang: SQL có LIMIT thật, use case trả tối đa limit, hasMore đúng ở đầu/cuối/rỗng. */
    @Test void limitsRowsAndComputesLastPageWithoutCountQuery() {
        var expected = new ArrayList<PendingDocumentListItem>();
        for (int i = 0; i < 25; i++) {
            long owner = newAccount(); var parameters = submission(owner, "IDENTITY_CARD", "PENDING");
            insert("submission", parameters); expected.add(item(owner, parameters));
        }
        assertEquals(expected.subList(0, 7), reads.searchPending(7, 0));
        var first = useCase.search(SearchPendingDocumentsQuery.from(null, null));
        assertEquals(expected.subList(0, 20), first.items()); assertTrue(first.hasMore());
        var last = useCase.search(SearchPendingDocumentsQuery.from(20, 20L));
        assertEquals(expected.subList(20, 25), last.items()); assertFalse(last.hasMore());
        var empty = useCase.search(SearchPendingDocumentsQuery.from(20, 25L));
        assertTrue(empty.items().isEmpty()); assertFalse(empty.hasMore());
    }

    /** Kiểm cột SELECT thật bằng metadata JDBC; mapper không được đọc đầy đủ rồi mới giấu trường nhạy cảm. */
    @Test void selectsExactlyFourMetadataColumns() {
        var columns = jdbc.query(sqlLoader.load("sql/identity/search_pending_documents.sql"),
                Map.of("fetchLimit", 1, "offset", 0), (ResultSetExtractor<List<String>>) rows -> {
                    var metadata = rows.getMetaData(); var names = new ArrayList<String>();
                    for (int i = 1; i <= metadata.getColumnCount(); i++) names.add(metadata.getColumnLabel(i));
                    return names;
                });
        assertEquals(List.of("code", "account_code", "document_type", "submitted_at"), columns);
    }

    /** Dùng connection thật và không transaction ngoài: proxy service phải mở transaction read-only. */
    @Test @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void serviceOpensReadOnlyTransaction() {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        doAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            return invocation.callRealMethod();
        }).when(reads).searchPending(21, 0);
        assertTrue(useCase.search(SearchPendingDocumentsQuery.from(null, null)).items().isEmpty());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    /** Cổng nội bộ không được đọc vô hạn do caller truyền fetchLimit âm, 0 hoặc lớn hơn cả hàng thăm dò. */
    @ParameterizedTest @ValueSource(ints = {-1, 0, 102, Integer.MAX_VALUE})
    void rejectsUnboundedInternalFetchLimit(int limit) {
        assertThrows(IllegalArgumentException.class, () -> reads.searchPending(limit, 0));
    }

    /** Dữ liệu sai ở biên port không được chạy OFFSET âm vào PostgreSQL. */
    @Test void rejectsNegativeInternalOffset() { assertThrows(IllegalArgumentException.class, () -> reads.searchPending(1, -1)); }

    /** Hàng chờ không chứa dữ liệu nhạy cảm trong log; SQL và bind vẫn đi qua SensitiveSql cả ở sql-log. */
    @Test void redactsQueueSqlAndNeverLoadsPrivateMaterial() {
        long owner = newAccount(); var parameters = submission(owner, "IDENTITY_CARD", "PENDING");
        insert("submission", parameters);
        var expected = List.of(item(owner, parameters));
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Logger binds = (Logger) LoggerFactory.getLogger("org.springframework.jdbc.core.StatementCreatorUtils");
        Level previous = binds.getLevel(); var logs = new ListAppender<ILoggingEvent>();
        logs.setContext(root.getLoggerContext()); logs.start(); root.addAppender(logs); binds.setLevel(Level.TRACE);
        try {
            assertEquals(expected, reads.searchPending(21, 0));
            var secrets = List.of(FAKE_NUMBER, "LOCAL FAKE DOCUMENT", "1990-01-01", (String) parameters.getValue("nonce"),
                    (String) parameters.getValue("ciphertext"), (String) parameters.getValue("hash"));
            for (var event : logs.list) {
                String text = event.getFormattedMessage();
                if (event.getThrowableProxy() != null) text += ThrowableProxyUtil.asString(event.getThrowableProxy());
                for (String secret : secrets) assertFalse(text.contains(secret), "Private document material leaked into queue logs.");
            }
            var p6 = logs.list.stream().filter(e -> e.getLoggerName().equals("p6spy")).toList();
            if (environment.acceptsProfiles(Profiles.of("sql-log"))) {
                assertEquals(1, p6.size()); assertTrue(p6.getFirst().getFormattedMessage().contains("[REDACTED sensitive SQL]"));
            } else assertTrue(p6.isEmpty());
        } finally { binds.setLevel(previous); root.detachAppender(logs); logs.stop(); }
    }

    /** Kết quả kỳ vọng map từ fixture biết trước, không gọi adapter thứ hai để tự chứng minh chính nó. */
    private PendingDocumentListItem item(long owner, MapSqlParameterSource parameters) {
        return new PendingDocumentListItem((String) parameters.getValue("code"), accountCode(owner),
                DocumentType.valueOf((String) parameters.getValue("type")), Instant.parse((String) parameters.getValue("submittedAt")));
    }
}
