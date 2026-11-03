package com.carrental.identity.adapter.out.persistence;

import com.carrental.PostgresTestConfiguration;
import com.carrental.identity.application.port.out.ReadDocumentSubmissionPort;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** V014 nâng từ dữ liệu V013 thật: không đoán ngày cấp, không sửa lịch sử hoặc hai con trỏ. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.flyway.target=13")
@Import(PostgresTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class DocumentIssueDateMigrationIntegrationTest extends DocumentDatabaseTestSupport {
    @Autowired Flyway flyway;
    @Autowired ReadDocumentSubmissionPort reads;

    /** Bản VERIFIED và PENDING cũ đều giữ NULL; đọc lại qua adapter thật không làm mất active. */
    @Test void preservesUnknownIssueDatesAndBothHeadPointers() {
        long owner = newAccount();
        var verified = submission(owner, "DRIVER_LICENSE", "VERIFIED");
        var pending = submission(owner, "DRIVER_LICENSE", "PENDING");
        long activeId = insert("legacy_submission", verified);
        long latestId = insert("legacy_submission", pending);
        insert("head", head(owner, "DRIVER_LICENSE", latestId, activeId));
        var beforeActive = jdbc.queryForMap(documentSql("read_submission"), Map.of("id", activeId));
        var beforeLatest = jdbc.queryForMap(documentSql("read_submission"), Map.of("id", latestId));
        var migration = Flyway.configure().configuration(flyway.getConfiguration()).target("14").load();
        assertEquals(1, migration.migrate().migrationsExecuted);
        assertEquals(beforeActive, jdbc.queryForMap(documentSql("read_submission"), Map.of("id", activeId)));
        assertEquals(beforeLatest, jdbc.queryForMap(documentSql("read_submission"), Map.of("id", latestId)));
        var head = jdbc.queryForMap(documentSql("read_head"), Map.of("accountId", owner, "type", "DRIVER_LICENSE"));
        assertEquals(activeId, head.get("active_verified_submission_id"));
        assertEquals(latestId, head.get("latest_submission_id"));
        assertNull(reads.findByCodeAndOwner((String) verified.getValue("code"), accountCode(owner)).orElseThrow().issuedOn());
        assertNull(reads.findByCodeAndOwner((String) pending.getValue("code"), accountCode(owner)).orElseThrow().issuedOn());
        assertNull(reads.listActiveByOwner(accountCode(owner)).getFirst().issuedOn());
        var metadata = reads.listHeadsByOwner(accountCode(owner)).getFirst();
        assertNull(metadata.latestSubmission().issuedOn());
        assertNull(metadata.activeVerifiedSubmission().issuedOn());
        assertEquals(0, migration.migrate().migrationsExecuted);
    }

    /** Lịch sử tự xác minh phải chặn migration; không âm thầm đổi người duyệt hoặc xoá bản cũ. */
    @Test void refusesSelfReviewedHistoryWithoutRewritingIt() {
        long owner = newAccount();
        long id = insert("legacy_submission", submission(owner, "DRIVER_LICENSE", "VERIFIED").addValue("reviewerId", owner));
        var before = jdbc.queryForMap(documentSql("read_submission"), Map.of("id", id));
        var migration = Flyway.configure().configuration(flyway.getConfiguration()).target("14").load();
        var failure = assertThrows(FlywayException.class, migration::migrate);
        assertTrue(failure.getMessage().contains("chk_document_submission_reviewer_not_owner"));
        assertEquals("013", flyway.info().current().getVersion().toString());
        assertEquals(before, jdbc.queryForMap(documentSql("read_submission"), Map.of("id", id)));
    }
}
