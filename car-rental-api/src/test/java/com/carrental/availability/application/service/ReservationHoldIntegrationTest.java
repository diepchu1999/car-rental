package com.carrental.availability.application.service;

import com.carrental.PostgresTestConfiguration;
import com.carrental.availability.api.ReservationRef;
import com.carrental.availability.application.command.HoldReservationCommand;
import com.carrental.availability.application.port.in.HoldReservationUseCase;
import com.carrental.availability.application.port.out.WriteReservationPort;
import com.carrental.availability.domain.Reservation;
import com.carrental.availability.domain.ReservationKind;
import com.carrental.availability.domain.ReservationPeriod;
import com.carrental.shared.code.BusinessCodeGenerator;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.shared.sql.SqlLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Kiểm service qua proxy Spring, port, adapter và PostgreSQL thật theo ADR-0005.
 *
 * <p>Chỉ thay bộ sinh mã để đếm chính xác số lần thử; chính sách cấu hình,
 * domain, transaction và SQL đều thật. Dùng database Testcontainers riêng,
 * rollback sau mỗi test. Đây chưa phải phép thử 50 luồng đồng thời.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "car-rental.availability.hold-duration=PT90M")
@Import(PostgresTestConfiguration.class)
@Transactional
class ReservationHoldIntegrationTest {

    private static final long VEHICLE_ID = 9_000_000_000_201L;
    private static final Instant START = Instant.parse("2030-10-01T03:00:00Z");
    private static final Instant END = START.plusSeconds(7200);

    @Autowired
    private HoldReservationUseCase useCase;
    @Autowired
    private WriteReservationPort writePort;
    @Autowired
    private NamedParameterJdbcTemplate jdbc;
    @Autowired
    private SqlLoader sqlLoader;
    @MockitoBean
    private BusinessCodeGenerator codeGenerator;

    private String readSql;

    /** Cung cấp mã xác định và câu đọc độc lập; không sửa SQL nghiệp vụ. */
    @BeforeEach
    void setUp() {
        when(codeGenerator.generate("KL")).thenReturn("KL-HOLD01", "KL-HOLD02", "KL-HOLD03");
        readSql = sqlLoader.load("sql/availability/read_reservation_for_constraint_test.sql");
    }

    /** Kiểm giữ chỗ thật dùng TTL cấu hình 90 phút, có đệm và rollback theo transaction ngoài. */
    @Test
    void persistsPolicyBufferAndRollsBackWithCaller() {
        ReservationRef ref = useCase.hold(command());
        assertEquals("KL-HOLD01", ref.code());
        assertTrue(ref.id() > 0);
        var rows = jdbc.query(readSql, Map.of("code", ref.code()), (rs, rowNum) -> {
            assertEquals(ref.id(), rs.getLong("id"));
            assertEquals(VEHICLE_ID, rs.getLong("vehicle_id"));
            assertEquals("HELD", rs.getString("status"));
            assertEquals("RENTAL", rs.getString("kind"));
            assertEquals("hold-integration-booking", rs.getString("booking_code"));
            assertEquals(START, rs.getObject("starts_at", OffsetDateTime.class).toInstant());
            assertEquals(END.plusSeconds(3600), rs.getObject("ends_at", OffsetDateTime.class).toInstant());
            Instant created = rs.getObject("created_at", OffsetDateTime.class).toInstant();
            assertEquals(created, rs.getObject("status_changed_at", OffsetDateTime.class).toInstant());
            assertEquals(created.plus(Duration.ofMinutes(90)),
                    rs.getObject("hold_expires_at", OffsetDateTime.class).toInstant());
            assertTrue(rs.getBoolean("start_inclusive"));
            assertFalse(rs.getBoolean("end_inclusive"));
            return rs.getLong("id");
        });
        assertEquals(1, rows.size());
        verify(codeGenerator).generate("KL");
        verifyNoMoreInteractions(codeGenerator);
        TestTransaction.flagForRollback();
        TestTransaction.end();
        TestTransaction.start();
        assertTrue(jdbc.queryForList(readSql, Map.of("code", ref.code())).isEmpty());
    }

    /** Kiểm xe bận thật gây VEHICLE_NOT_AVAILABLE và không sinh mã lần hai. */
    @Test
    void busyVehicleRaisesConflictWithoutRetryingCode() {
        writePort.insert(Reservation.createBlocked("KL-BUSY01", VEHICLE_ID,
                ReservationPeriod.finite(START, END), ReservationKind.MAINTENANCE,
                "Scheduled maintenance", START.minusSeconds(86400))).orElseThrow();
        DomainException failure = assertThrows(DomainException.class, () -> useCase.hold(command()));
        assertEquals(ErrorCode.VEHICLE_NOT_AVAILABLE, failure.errorCode());
        assertEquals(DomainException.Category.CONFLICT, failure.category());
        verify(codeGenerator).generate("KL");
        verifyNoMoreInteractions(codeGenerator);
    }

    /** Kiểm xung đột mã thật không làm hỏng transaction và thử đúng mã thứ hai trên xe rảnh. */
    @Test
    void actualCodeCollisionRetriesWithoutAbortingTransaction() {
        writePort.insert(Reservation.createBlocked("KL-HOLD01", VEHICLE_ID + 1,
                ReservationPeriod.finite(START, END), ReservationKind.MAINTENANCE,
                null, START.minusSeconds(86400))).orElseThrow();
        ReservationRef ref = useCase.hold(command());
        assertEquals("KL-HOLD02", ref.code());
        assertEquals(1, jdbc.queryForList(readSql, Map.of("code", ref.code())).size());
        assertEquals(1, jdbc.queryForList(readSql, Map.of("code", "KL-HOLD01")).size());
        verify(codeGenerator, times(2)).generate("KL");
        verifyNoMoreInteractions(codeGenerator);
    }

    /** Tạo command với đệm một giờ để kiểm toàn bộ luồng tự áp dụng đệm. */
    private static HoldReservationCommand command() {
        return HoldReservationCommand.from(VEHICLE_ID, START, END,
                Duration.ofHours(1), "hold-integration-booking");
    }
}
