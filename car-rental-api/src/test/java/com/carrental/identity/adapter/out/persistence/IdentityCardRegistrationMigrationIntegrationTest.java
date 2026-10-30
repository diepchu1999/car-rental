package com.carrental.identity.adapter.out.persistence;

import com.carrental.PostgresTestConfiguration;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** V013 nâng từ V012 có dữ liệu: backfill đúng, không tự chọn chủ nếu lịch sử đã mâu thuẫn. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.flyway.target=12")
@Import(PostgresTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class IdentityCardRegistrationMigrationIntegrationTest extends DocumentDatabaseTestSupport {
    @Autowired Flyway flyway;

    /** Nhiều bản VERIFIED cùng chủ gom một claim; pending của chủ khác không giữ chỗ. */
    @Test void backfillsVerifiedHistoryWithoutReservingPending() {
        assertEquals("012", flyway.info().current().getVersion().toString());
        long owner = newAccount();
        var first = submission(owner, "IDENTITY_CARD", "VERIFIED");
        var second = submission(owner, "IDENTITY_CARD", "VERIFIED");
        var pending = submission(newAccount(), "IDENTITY_CARD", "PENDING", fakeNumber(accountCode(owner)));
        // V012 chưa có bảng đăng ký: cố ý không dùng helper tạo fixture VERIFIED của V013.
        jdbc.queryForObject(documentSql("insert_submission"), first, Long.class);
        jdbc.queryForObject(documentSql("insert_submission"), second, Long.class);
        jdbc.queryForObject(documentSql("insert_submission"), pending, Long.class);
        var upgraded = Flyway.configure().configuration(flyway.getConfiguration()).target("13").load();
        assertEquals(1, upgraded.migrate().migrationsExecuted);
        assertEquals(List.of(accountCode(owner)), jdbc.queryForList(documentSql("read_registration_for_test"), Map.of("code", first.getValue("code")), String.class));
        assertEquals(0, upgraded.migrate().migrationsExecuted);
    }

    /** Số đã VERIFIED ở hai chủ trước migration phải dừng với thông báo an toàn, không mất dữ liệu. */
    @Test void refusesConflictingLegacyOwnersWithoutLeakingHash() {
        var first = submission(newAccount(), "IDENTITY_CARD", "VERIFIED", FAKE_NUMBER);
        var second = submission(newAccount(), "IDENTITY_CARD", "VERIFIED", FAKE_NUMBER);
        jdbc.queryForObject(documentSql("insert_submission"), first, Long.class);
        jdbc.queryForObject(documentSql("insert_submission"), second, Long.class);
        var upgraded = Flyway.configure().configuration(flyway.getConfiguration()).target("13").load();
        var failure = assertThrows(FlywayException.class, upgraded::migrate);
        assertTrue(failure.getMessage().contains("conflicting owners"));
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            assertFalse(String.valueOf(cause.getMessage()).contains((String) first.getValue("hash")));
        }
        assertEquals("012", flyway.info().current().getVersion().toString());
        assertNotNull(jdbc.queryForObject(documentSql("read_registration_absent_for_test"), Map.of(), Boolean.class));
        assertTrue(jdbc.queryForObject(documentSql("read_registration_absent_for_test"), Map.of(), Boolean.class));
    }
}
