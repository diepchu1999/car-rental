package com.carrental.availability.application.service;

import com.carrental.PostgresTestConfiguration;
import com.carrental.availability.api.AvailabilityDirectory;
import com.carrental.availability.api.Period;
import com.carrental.availability.application.port.out.ReadReservationPort;
import com.carrental.shared.code.BusinessCodeGenerator;
import com.carrental.shared.sql.SqlLoader;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Kiểm hạn trả từ Directory bằng PostgreSQL và rollback khi đọc lại lỗi (BR-103, ADR-0014).
 * Không có transaction bao ngoài test: chỉ transaction của use case bảo vệ INSERT.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "car-rental.availability.hold-duration=PT37M")
@Import(PostgresTestConfiguration.class)
class ReservationHoldResultIntegrationTest {
    private static final Instant NOW = Instant.parse("2030-09-01T00:00:00.123456789Z");
    private static final Period PERIOD = new Period(Instant.parse("2030-10-01T03:00:00Z"), Instant.parse("2030-10-02T03:00:00Z"));
    @Autowired private AvailabilityDirectory directory;
    @Autowired private NamedParameterJdbcTemplate jdbc;
    @Autowired private SqlLoader sql;
    @MockitoBean(name = "applicationClock") private Clock clock;
    @MockitoBean private BusinessCodeGenerator codes;
    @MockitoSpyBean private ReadReservationPort reads;

    /** Đồng hồ có nano giây để test phát hiện trả hạn trong RAM thay cho hạn PostgreSQL lưu. */
    @BeforeEach
    void setUp() { when(clock.instant()).thenReturn(NOW); }

    /** Hạn trả ra khớp từng micro giây với CSDL, không phải hạn chưa làm tròn của aggregate. */
    @Test
    void returnsExactStoredExpiryAfterPostgresPrecisionConversion() {
        when(codes.generate("KL")).thenReturn("KL-RET001");
        var ref = directory.hold(9_000_000_001_001L, PERIOD, Duration.ofHours(2), "DT-3009-RET001");
        var storedExpiry = jdbc.queryForObject(sql.load("sql/availability/read_reservation_for_constraint_test.sql"),
                Map.of("code", ref.code()), (rs, rowNumber) -> rs.getObject("hold_expires_at", OffsetDateTime.class).toInstant());
        assertEquals(storedExpiry, ref.holdExpiresAt());
        assertEquals(0, ref.holdExpiresAt().getNano() % 1_000);
        assertNotEquals(NOW.plus(Duration.ofMinutes(37)), ref.holdExpiresAt());
        verify(reads).loadAggregate(ref.code());
        verify(codes).generate("KL");
    }

    /** INSERT thắng nhưng reload thiếu phải rollback chỗ giữ, không để khoá lịch mồ côi. */
    @Test
    void missingReloadRollsBackInsertedHold() {
        when(codes.generate("KL")).thenReturn("KL-RET002");
        doReturn(Optional.empty()).when(reads).loadAggregate("KL-RET002");
        assertThrows(IllegalStateException.class, () -> directory.hold(9_000_000_001_002L, PERIOD,
                Duration.ofHours(2), "DT-3009-RET002"));
        assertMissing("KL-RET002");
        verify(codes).generate("KL");
    }

    /** Lỗi đọc truyền nguyên ra ngoài, không đổi thành xe bận hoặc thử sinh mã lần hai. */
    @Test
    void reloadFailureRollsBackInsertedHold() {
        when(codes.generate("KL")).thenReturn("KL-RET003");
        var failure = new IllegalStateException("Simulated reload failure.");
        doThrow(failure).when(reads).loadAggregate("KL-RET003");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> directory.hold(9_000_000_001_003L,
                PERIOD, Duration.ofHours(2), "DT-3009-RET003")));
        assertMissing("KL-RET003");
        verify(codes).generate("KL");
    }

    /** Đọc độc lập sau khi use case đã rollback, không đi qua spy dùng để gây lỗi. */
    private void assertMissing(String code) {
        assertTrue(jdbc.queryForList(sql.load("sql/availability/read_reservation_for_constraint_test.sql"),
                Map.of("code", code)).isEmpty());
    }
}
