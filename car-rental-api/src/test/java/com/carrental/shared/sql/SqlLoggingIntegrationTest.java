package com.carrental.shared.sql;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.carrental.PostgresTestConfiguration;
import com.carrental.branch.api.BranchDirectory;
import com.carrental.branch.application.command.CreateBranchCommand;
import com.carrental.branch.application.port.in.CreateBranchUseCase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Types;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Security guideline §3: kiểm Boot/P6Spy bằng PostgreSQL thật, không dùng dữ liệu local.
 * Không tự bật profile: suite thường kiểm không proxy/không SQL log; lượt chạy sql-log kiểm bật thật.
 * PostgresTestConfiguration xác nhận datasource và pool của mỗi context đúng mode trước khi chạy test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
class SqlLoggingIntegrationTest {
    @Autowired private NamedParameterJdbcTemplate jdbc;
    @Autowired private SqlLoader sqlLoader;
    @Autowired private Environment environment;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private CreateBranchUseCase createBranch;
    @Autowired private BranchDirectory branches;

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    /** Thu sự kiện P6Spy thật, không tự nâng mức log hoặc sửa formatter để làm test xanh. */
    @BeforeEach
    void captureSqlEvents() {
        logger = (Logger) LoggerFactory.getLogger("p6spy");
        appender = new ListAppender<>();
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.addAppender(appender);
    }

    /** Gỡ appender sau từng test, không làm rò bộ thu log sang context hoặc lớp khác. */
    @AfterEach
    void detachSqlEvents() {
        logger.detachAppender(appender);
        appender.stop();
    }

    /** Mỗi SELECT được log một lần với ms, chuỗi có dấu nháy, số, null và SQL nhiều dòng nguyên vẹn. */
    @Test
    void logsEveryExecutionWithExpandedValuesAndPreservedNewlinesOnlyWhenEnabled() {
        String sql = sqlLoader.load("sql/shared/sql_logging_probe.sql");
        List<String> labels = List.of("sql-log-probe-O'Brien", "sql-log-probe-second");
        for (String label : labels) {
            var parameters = new MapSqlParameterSource()
                    .addValue("label", label, Types.VARCHAR)
                    .addValue("quantity", 7, Types.INTEGER)
                    .addValue("optionalValue", null, Types.VARCHAR);
            var row = jdbc.queryForMap(sql, parameters);
            assertEquals(label, row.get("label"));
            assertEquals(7, row.get("quantity"));
            assertNull(row.get("optional_value"));
        }
        List<String> messages = sqlMessages();
        if (!sqlLoggingEnabled()) {
            assertTrue(messages.isEmpty(), "Default mode must produce no P6Spy SQL events.");
            return;
        }
        assertEquals(2, messages.size(), "Each execution must have exactly one SQL event.");
        for (int index = 0; index < labels.size(); index++) {
            String message = messages.get(index);
            String quotedLabel = "'" + labels.get(index).replace("'", "''") + "'";
            assertTrue(message.matches("(?s)^SQL \\(\\d+ ms\\):\\n.*"), message);
            assertTrue(message.contains("\ncaller: shared.sql.SqlLoggingIntegrationTest."
                    + "logsEveryExecutionWithExpandedValuesAndPreservedNewlinesOnlyWhenEnabled"
                    + "(SqlLoggingIntegrationTest.java:"), message);
            assertFalse(message.contains("caller: com.carrental."), message);
            assertTrue(message.contains("-- Kiểm tương thích SQL log: dữ liệu giả, giữ nguyên dấu xuống dòng.\nSELECT"),
                    "The leading SQL comment must end before SELECT: " + message);
            assertTrue(message.contains("CAST(" + quotedLabel + " AS text)"), message);
            assertTrue(message.contains("CAST(7 AS integer)"), message);
            assertTrue(message.contains("CAST(NULL AS text)"), message);
            assertFalse(message.contains(":label"), message);
        }
    }

    /** Proxy không đổi transaction thật: nhìn thấy bản ghi trong lượt ghi nhưng rollback không để lại dữ liệu. */
    @Test
    void preservesApplicationTransactionAndRollback() {
        var created = Objects.requireNonNull(new TransactionTemplate(transactions).execute(status -> {
            var branch = createBranch.create(CreateBranchCommand.from(10.76, 106.66,
                    "SQL logging rollback probe", "Fake local address"));
            assertTrue(branches.findByCode(branch.code()).isPresent());
            status.setRollbackOnly();
            return branch;
        }));
        assertTrue(branches.findByCode(created.code()).isEmpty(), "The outer transaction must really roll back.");
        List<String> messages = sqlMessages();
        if (sqlLoggingEnabled()) {
            assertTrue(messages.stream().anyMatch(message -> message.contains("INSERT INTO branch.branch")
                    && message.contains("'" + created.code() + "'")), "The actual application INSERT must pass through P6Spy.");
        } else {
            assertTrue(messages.isEmpty(), "Default application writes must bypass P6Spy too.");
        }
    }

    /** Đọc profile từ context thật; không bật bằng annotation hay giá trị mặc định trong test. */
    private boolean sqlLoggingEnabled() {
        return environment.acceptsProfiles(Profiles.of("sql-log"));
    }

    /** Lấy thông điệp đã format của sự kiện thực tế, gồm cả dấu xuống dòng và thời gian P6Spy đo. */
    private List<String> sqlMessages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }
}
