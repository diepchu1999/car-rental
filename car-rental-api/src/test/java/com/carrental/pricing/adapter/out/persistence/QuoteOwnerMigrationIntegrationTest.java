package com.carrental.pricing.adapter.out.persistence;

import com.carrental.PostgresTestConfiguration;
import com.carrental.identity.adapter.in.internal.ServiceCallerTestScope;
import com.carrental.identity.api.CallerRef;
import com.carrental.pricing.api.QuoteDirectory;
import com.carrental.pricing.application.port.in.GetQuoteUseCase;
import com.carrental.pricing.application.port.out.ReadQuotePort;
import com.carrental.pricing.application.query.GetQuoteQuery;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.shared.sql.SqlLoader;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** BR-122: nâng V014 lên V015 có dữ liệu thật; không sửa V009, không đoán chủ hoặc xoá báo giá cũ. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.flyway.target=14")
@Import(PostgresTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class QuoteOwnerMigrationIntegrationTest {
    @Autowired Flyway flyway;
    @Autowired NamedParameterJdbcTemplate jdbc;
    @Autowired SqlLoader loader;
    @Autowired ReadQuotePort reads;
    @Autowired GetQuoteUseCase get;
    @Autowired QuoteDirectory directory;

    /** Giữ NULL lịch sử nhưng không cấp quyền; CHECK NOT VALID vẫn chặn báo giá mới thiếu chủ. */
    @Test
    void preservesLegacySnapshotButDeniesReadingAndBookingAndRequiresNewOwner() {
        String code = "BG-2611-LEGACY";
        var parameters = Map.of("code", code);
        var insert = loader.load("sql/pricing/insert_legacy_quote.sql");
        long id = jdbc.queryForObject(insert, parameters, Long.class);
        jdbc.update(loader.load("sql/pricing/insert_legacy_quote_line.sql"), Map.of("quoteId", id));
        var snapshotSql = loader.load("sql/pricing/read_legacy_quote_snapshot.sql");
        var before = jdbc.queryForMap(snapshotSql, parameters);
        var linesSql = loader.load("sql/pricing/find_quote_lines.sql");
        var lines = jdbc.queryForList(linesSql, Map.of("quoteId", id));
        var upgraded = Flyway.configure().configuration(flyway.getConfiguration()).target("15").load();
        assertEquals(1, upgraded.migrate().migrationsExecuted);
        assertEquals(before, jdbc.queryForMap(snapshotSql, parameters));
        assertEquals(lines, jdbc.queryForList(linesSql, Map.of("quoteId", id)));
        assertNull(reads.findByCode(code).orElseThrow().customerAccountCode());
        try (var ignored = ServiceCallerTestScope.open(new CallerRef("TK-OWNER1", Set.of("CUSTOMER"), null))) {
            assertEquals(ErrorCode.QUOTE_NOT_FOUND,
                    assertThrows(DomainException.class, () -> get.get(GetQuoteQuery.from(code))).errorCode());
        }
        assertEquals(ErrorCode.QUOTE_NOT_FOUND, assertThrows(DomainException.class,
                () -> directory.getForBooking(code, "TK-OWNER1", Instant.parse("2026-11-01T03:00:01Z"))).errorCode());
        var failure = assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.queryForObject(insert, Map.of("code", "BG-2611-NEW001"), Long.class));
        var cause = assertInstanceOf(PSQLException.class, failure.getMostSpecificCause());
        assertEquals("23514", cause.getSQLState());
        assertEquals("chk_quote_owner_required", cause.getServerErrorMessage().getConstraint());
        assertEquals(0, upgraded.migrate().migrationsExecuted);
    }
}
