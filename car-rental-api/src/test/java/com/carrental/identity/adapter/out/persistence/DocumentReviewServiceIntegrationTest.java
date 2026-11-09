package com.carrental.identity.adapter.out.persistence;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.carrental.PostgresTestConfiguration;
import com.carrental.identity.api.CallerDirectory;
import com.carrental.identity.api.CallerRef;
import com.carrental.identity.application.command.RejectDocumentCommand;
import com.carrental.identity.application.command.VerifyDocumentCommand;
import com.carrental.identity.application.port.in.RejectDocumentUseCase;
import com.carrental.identity.application.port.in.VerifyDocumentUseCase;
import com.carrental.identity.application.port.out.ReadDocumentSubmissionPort;
import com.carrental.identity.domain.AccountRole;
import com.carrental.identity.domain.DocumentSubmissionStatus;
import com.carrental.identity.domain.DocumentType;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.shared.sql.SensitiveSql;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.slf4j.LoggerFactory;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Service proxy và PostgreSQL thật, không transaction test: chứng minh commit/rollback do use case quản lý. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DocumentReviewServiceIntegrationTest extends DocumentDatabaseTestSupport {
    private static final Instant NOW = Instant.parse("2026-10-30T03:00:00.123456789Z");
    @Autowired VerifyDocumentUseCase verifyUseCase;
    @Autowired RejectDocumentUseCase rejectUseCase;
    @MockitoBean CallerDirectory callers;
    @MockitoBean Clock clock;
    @MockitoSpyBean ReadDocumentSubmissionPort reads;
    @Autowired Environment environment;
    private String reviewer;

    /** Người duyệt là tài khoản thật khác chủ; chỉ caller và đồng hồ được giả ở biên hệ thống. */
    @BeforeEach void setUp() {
        reviewer = accountCode(newAccount());
        when(callers.current()).thenReturn(new CallerRef(reviewer, Set.of("DOCUMENT_VERIFIER"), null));
        when(clock.instant()).thenReturn(NOW);
    }

    /** Hai vai không có chi nhánh đều commit được CCCD/GPLX, trả metadata khớp hàng đọc độc lập. */
    @ParameterizedTest @CsvSource({"ADMIN,IDENTITY_CARD", "ADMIN,DRIVER_LICENSE", "DOCUMENT_VERIFIER,IDENTITY_CARD", "DOCUMENT_VERIFIER,DRIVER_LICENSE"})
    void commitsVerificationWithServerReviewerAndReload(String role, DocumentType type) {
        when(callers.current()).thenReturn(new CallerRef(reviewer, Set.of(role), null));
        var fixture = pending(type);
        var result = verifyUseCase.verify(VerifyDocumentCommand.from(fixture.code()));
        var stored = row(fixture.code());
        assertEquals(DocumentSubmissionStatus.VERIFIED, result.status());
        assertEquals(type, result.documentType());
        assertEquals(NOW.truncatedTo(ChronoUnit.MICROS), result.reviewedAt());
        assertEquals("VERIFIED", stored.get("status")); assertEquals(reviewer, stored.get("reviewer_code"));
        assertEquals(fixture.code(), stored.get("active_code")); assertEquals(fixture.code(), stored.get("latest_code"));
        assertEquals("VERIFIED", row(fixture.oldCode()).get("status"));
        assertEquals(Optional.of(result), reads.findStatusByCodeAndOwner(fixture.code(), fixture.owner()));
        assertEquals(type == DocumentType.IDENTITY_CARD ? List.of(fixture.owner()) : List.of(), registrations(fixture.code()));
    }

    /** Từ chối commit ghi chú giữ nguyên, chủ đọc được kết quả và bản VERIFIED cũ còn active. */
    @ParameterizedTest @EnumSource(DocumentType.class)
    void commitsRejectionAndKeepsOldActive(DocumentType type) {
        var fixture = pending(type);
        var command = RejectDocumentCommand.from(fixture.code(), "ILLEGIBLE", "Local fake note\nsecond line");
        var result = rejectUseCase.reject(command);
        var stored = row(fixture.code());
        assertEquals(DocumentSubmissionStatus.REJECTED, result.status());
        assertEquals("ILLEGIBLE", result.rejectionCode()); assertEquals(command.rejectionNote(), result.rejectionNote());
        assertEquals(NOW.truncatedTo(ChronoUnit.MICROS), result.reviewedAt());
        assertEquals(reviewer, stored.get("reviewer_code"));
        assertEquals(fixture.oldCode(), stored.get("active_code"));
        assertEquals(Optional.of(result), reads.findStatusByCodeAndOwner(fixture.code(), fixture.owner()));
        assertTrue(registrations(fixture.code()).isEmpty());
    }

    /** Vai khác bị chặn ở service proxy thật, bản PENDING và active không đổi sau hai thao tác. */
    @ParameterizedTest @EnumSource(value = AccountRole.class, names = {"ADMIN", "DOCUMENT_VERIFIER"}, mode = EnumSource.Mode.EXCLUDE)
    void deniedRolesCannotChangeDatabase(AccountRole role) {
        var fixture = pending(DocumentType.IDENTITY_CARD);
        when(callers.current()).thenReturn(new CallerRef(reviewer, Set.of(role.name()), role.isBranchScoped() ? 1L : null));
        assertCode(ErrorCode.ACCESS_DENIED, () -> verifyUseCase.verify(VerifyDocumentCommand.from(fixture.code())));
        assertCode(ErrorCode.ACCESS_DENIED, () -> rejectUseCase.reject(RejectDocumentCommand.from(fixture.code(), "ILLEGIBLE", null)));
        assertPending(fixture);
    }

    /** Reload lỗi sau write phải rollback transaction ngoài, không chỉ savepoint trong adapter. */
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void reloadFailureRollsBackWholeDecision(boolean verifying) {
        var fixture = pending(DocumentType.IDENTITY_CARD);
        doReturn(Optional.empty()).when(reads).findStatusForReviewByCode(fixture.code());
        if (verifying) assertThrows(IllegalStateException.class, () -> verifyUseCase.verify(VerifyDocumentCommand.from(fixture.code())));
        else assertThrows(IllegalStateException.class, () -> rejectUseCase.reject(RejectDocumentCommand.from(fixture.code(), "ILLEGIBLE", "Local fake note")));
        assertPending(fixture);
    }

    /** Lặp quyết định giữ nguyên 409 của adapter, không retry và không ghi đè người duyệt trước. */
    @Test void completedDecisionRemainsConflict() {
        var fixture = pending(DocumentType.IDENTITY_CARD);
        verifyUseCase.verify(VerifyDocumentCommand.from(fixture.code()));
        var stored = row(fixture.code());
        assertCode(ErrorCode.DOCUMENT_REVIEW_CONFLICT, () -> verifyUseCase.verify(VerifyDocumentCommand.from(fixture.code())));
        assertCode(ErrorCode.DOCUMENT_REVIEW_CONFLICT, () -> rejectUseCase.reject(RejectDocumentCommand.from(fixture.code(), "ILLEGIBLE", null)));
        assertEquals(stored, row(fixture.code()));
    }

    /** Không tồn tại giữ đúng 404, không biến lỗi adapter thành kết quả thành công hoặc lỗi reload. */
    @Test void missingDocumentRemainsNotFound() {
        assertCode(ErrorCode.DOCUMENT_NOT_FOUND, () -> verifyUseCase.verify(VerifyDocumentCommand.from("GT-NONE00")));
        assertCode(ErrorCode.DOCUMENT_NOT_FOUND, () -> rejectUseCase.reject(RejectDocumentCommand.from("GT-NONE00", "ILLEGIBLE", null)));
        assertTrue(reads.findStatusForReviewByCode("GT-NONE00").isEmpty());
    }

    /** Chủ khác đã được xác minh cùng số thì use case trả đúng mã, không làm mất pending của bên thua. */
    @Test void existingCardOwnerKeepsClaim() {
        String number = "LOCAL-FAKE-" + UUID.randomUUID();
        var first = pending(DocumentType.IDENTITY_CARD, number);
        var second = pending(DocumentType.IDENTITY_CARD, number);
        verifyUseCase.verify(VerifyDocumentCommand.from(first.code()));
        assertCode(ErrorCode.IDENTITY_CARD_ALREADY_REGISTERED, () -> verifyUseCase.verify(VerifyDocumentCommand.from(second.code())));
        assertEquals("PENDING", row(second.code()).get("status"));
        assertEquals(second.oldCode(), row(second.code()).get("active_code"));
        assertEquals(List.of(first.owner()), registrations(second.code()));
    }

    /** Đường ghi rồi reload thật không lộ mã hồ sơ/người duyệt/ghi chú ở TRACE hoặc sql-log. */
    @Test void redactsDecisionAndReloadLogs() {
        var fixture = pending(DocumentType.IDENTITY_CARD);
        String note = "LOCAL-FAKE-NOTE-" + UUID.randomUUID();
        var root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var binds = (Logger) LoggerFactory.getLogger("org.springframework.jdbc.core.StatementCreatorUtils");
        Level previous = binds.getLevel();
        var logs = new ListAppender<ILoggingEvent>();
        logs.setContext(root.getLoggerContext()); logs.start(); root.addAppender(logs); binds.setLevel(Level.TRACE);
        try {
            rejectUseCase.reject(RejectDocumentCommand.from(fixture.code(), "ILLEGIBLE", note));
            for (var event : logs.list) {
                String text = event.getFormattedMessage();
                if (event.getThrowableProxy() != null) text += ThrowableProxyUtil.asString(event.getThrowableProxy());
                for (String secret : List.of(fixture.code(), reviewer, note)) {
                    assertFalse(text.contains(secret), "Sensitive review metadata leaked.");
                }
            }
            var reloadEvents = logs.list.stream().filter(event -> event.getLoggerName().equals("p6spy")
                    && event.getFormattedMessage().contains("DocumentSubmissionReadAdapter")).toList();
            if (environment.acceptsProfiles(Profiles.of("sql-log"))) {
                assertFalse(reloadEvents.isEmpty());
                assertTrue(reloadEvents.stream().allMatch(event -> event.getFormattedMessage().contains("[REDACTED sensitive SQL]")));
            } else assertTrue(reloadEvents.isEmpty());
        } finally {
            binds.setLevel(previous); root.detachAppender(logs); logs.stop();
        }
    }

    /** Tạo số riêng cho mỗi lần kiểm, tránh claim đã commit của test trước. */
    private Fixture pending(DocumentType type) { return pending(type, "LOCAL-FAKE-" + UUID.randomUUID()); }

    /** Fixture có bản VERIFIED cũ và pending mới khác số; không cần mở ảnh để xử lý quyết định. */
    private Fixture pending(DocumentType type, String number) {
        long ownerId = newAccount(); String owner = accountCode(ownerId);
        var old = submission(ownerId, type.name(), "VERIFIED"); long oldId = insert("submission", old);
        var current = submission(ownerId, type.name(), "PENDING", number); long currentId = insert("submission", current);
        insert("head", head(ownerId, type.name(), currentId, oldId));
        return new Fixture((String) current.getValue("code"), owner, (String) old.getValue("code"));
    }

    /** Chỉ lưu mã fixture, không đưa số/hash hoặc giấy tờ vào record. */
    private record Fixture(String code, String owner, String oldCode) { }

    /** Đọc bằng connection độc lập sau khi proxy đã commit hoặc rollback. */
    private Map<String, Object> row(String code) {
        return SensitiveSql.execute(() -> jdbc.queryForMap(documentSql("read_review_for_test"), Map.of("code", code)));
    }

    /** Truy chủ claim bằng mã TK; không đưa hash ra output test. */
    private List<String> registrations(String code) {
        return SensitiveSql.execute(() -> jdbc.queryForList(documentSql("read_registration_for_test"), Map.of("code", code), String.class));
    }

    /** Kiểm đủ rollback quyết định, reviewer, ghi chú, con trỏ và claim mới. */
    private void assertPending(Fixture fixture) {
        var stored = row(fixture.code());
        assertEquals("PENDING", stored.get("status")); assertNull(stored.get("reviewer_code"));
        assertNull(stored.get("reviewed_at")); assertNull(stored.get("rejection_note"));
        assertEquals(fixture.code(), stored.get("latest_code")); assertEquals(fixture.oldCode(), stored.get("active_code"));
        assertTrue(registrations(fixture.code()).isEmpty());
    }

    /** Chỉ nhận đúng mã nghiệp vụ; lỗi SQL, transaction hoặc mock không được tính là đạt. */
    private static void assertCode(ErrorCode expected, org.junit.jupiter.api.function.Executable action) {
        var failure = assertThrows(DomainException.class, action);
        assertEquals(expected, failure.errorCode());
    }
}
