package com.carrental.audit;

import com.carrental.PostgresTestConfiguration;
import com.carrental.audit.api.AuditDirectory;
import com.carrental.shared.sql.SqlLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** BR-130: cổng thật, transaction thật và PostgreSQL thật; không dùng dữ liệu local. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
class AuditIntegrationTest {
    @Autowired AuditDirectory audit;
    @Autowired NamedParameterJdbcTemplate jdbc;
    @Autowired SqlLoader loader;
    @Autowired PlatformTransactionManager transactions;

    /** Hai lượt mở cùng requestId vẫn là hai event; đọc lại bằng connection ngoài transaction ghi. */
    @Test void recordsEveryViewWithActorSubjectResourceAndRequestId() {
        String request = UUID.randomUUID().toString();
        audit.recordDocumentView("TK-ACTOR1", "TK-OWNER1", "GT-DOC001", request);
        audit.recordDocumentView("TK-ACTOR1", "TK-OWNER1", "GT-DOC001", request);
        var rows = jdbc.queryForList(loader.load("sql/audit/read_events.sql"), Map.of("requestId", request));
        assertEquals(2, rows.size());
        for (var row : rows) {
            assertEquals("TK-ACTOR1", row.get("actor_account_code"));
            assertEquals("TK-OWNER1", row.get("subject_account_code"));
            assertEquals("GT-DOC001", row.get("resource_code"));
            assertEquals("DOCUMENT_VIEW_OPENED", row.get("action"));
            assertEquals(request, row.get("request_id"));
            assertNotNull(row.get("occurred_at"));
        }
    }

    /** Audit đã commit độc lập trước khi cổng trở về; caller rollback không làm mất lượt mở. */
    @Test void commitsBeforeReturningEvenWhenCallerRollsBack() {
        String request = UUID.randomUUID().toString();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            audit.recordDocumentView("TK-ACTOR1", "TK-OWNER1", "GT-DOC001", request);
            status.setRollbackOnly();
        });
        assertEquals(1L, count(request));
    }

    /** Trigger thật chặn UPDATE/DELETE/TRUNCATE đúng SQLSTATE và tên constraint, không chỉ ném lỗi bất kỳ. */
    @ParameterizedTest
    @ValueSource(strings = {"update_event", "delete_event", "truncate_events"})
    void databaseRejectsMutation(String statement) {
        String request = UUID.randomUUID().toString();
        audit.recordDocumentView("TK-ACTOR1", "TK-OWNER1", "GT-DOC001", request);
        var failure = assertThrows(DataAccessException.class, () ->
                new TransactionTemplate(transactions).executeWithoutResult(status ->
                        jdbc.update(loader.load("sql/audit/" + statement + ".sql"), Map.of("requestId", request))));
        var postgres = assertInstanceOf(PSQLException.class, failure.getMostSpecificCause());
        assertEquals("23514", postgres.getSQLState());
        assertEquals("chk_audit_event_append_only", postgres.getServerErrorMessage().getConstraint());
        assertEquals("audit_event", postgres.getServerErrorMessage().getTable());
        assertEquals(1L, count(request));
    }

    /** Đầu vào lỗi không được tạo event hoặc bị phản chiếu vào message. */
    @Test void rejectsUnsafeRequestIdWithoutEchoingIt() {
        String unsafe = "secret-sentinel\nforged-line";
        var failure = assertThrows(IllegalArgumentException.class, () ->
                audit.recordDocumentView("TK-ACTOR1", "TK-OWNER1", "GT-DOC001", unsafe));
        assertFalse(failure.toString().contains("secret-sentinel"));
        assertEquals(0L, count(unsafe));
    }

    /** Đếm qua cổng SQL đọc thật, không suy kết quả từ lời gọi mock. */
    private long count(String requestId) {
        return jdbc.queryForObject(loader.load("sql/audit/count_events.sql"), Map.of("requestId", requestId), Long.class);
    }
}
