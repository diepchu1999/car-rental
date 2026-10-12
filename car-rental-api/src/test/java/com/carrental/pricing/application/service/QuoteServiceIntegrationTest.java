package com.carrental.pricing.application.service;

import com.carrental.PostgresTestConfiguration;
import com.carrental.pricing.application.command.CreateQuoteCommand;
import com.carrental.pricing.application.port.in.CreateQuoteUseCase;
import com.carrental.pricing.application.port.in.GetQuoteUseCase;
import com.carrental.pricing.application.port.out.*;
import com.carrental.pricing.application.query.GetQuoteQuery;
import com.carrental.pricing.application.view.QuoteDetail;
import com.carrental.pricing.domain.*;
import com.carrental.shared.code.BusinessCodeGenerator;
import com.carrental.shared.rental.*;
import com.carrental.shared.sql.SqlLoader;
import com.carrental.vehicle.application.port.out.WriteVehiclePort;
import com.carrental.vehicle.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.*;
import java.util.ArrayList;
import java.util.Optional;
import static com.carrental.pricing.domain.PricingTestFixtures.*;
import static com.carrental.pricing.domain.QuoteTestFixtures.*;
import static com.carrental.vehicle.VehicleTestFixtures.SPECIFICATIONS;
import static org.junit.jupiter.api.Assertions.*;

/** Giao dịch commit/rollback và bất biến qua đổi giá/cấu hình/Clock thật; không có transaction bao ngoài test. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestConfiguration.class, QuoteServiceIntegrationTest.ProbeConfiguration.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class QuoteServiceIntegrationTest {
    @Autowired private CreateQuoteUseCase create;
    @Autowired private GetQuoteUseCase get;
    @Autowired private WriteVehiclePort vehicleWrites;
    @Autowired private WriteQuotePort quoteWrites;
    @Autowired @Qualifier("quoteReadAdapter") private ReadQuotePort actualReads;
    @Autowired private ReadProbe probe;
    @Autowired private MutablePolicy policy;
    @Autowired private MutableClock clock;
    @Autowired private BusinessCodeGenerator codes;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private NamedParameterJdbcTemplate jdbc;
    @Autowired private SqlLoader loader;

    /** Tách trạng thái nguồn cấu hình/đồng hồ và probe giữa các phép kiểm. */
    @BeforeEach
    void reset() { policy.value = parameters(); clock.at = CREATED_AT; probe.fail = false; probe.last = null; }

    /** Báo giá cũ giữ nguyên sau đổi giá, tham số bảo hiểm/mức tăng/hạn; báo giá mới dùng bộ mới. */
    @Test
    void oldQuoteIsImmutableAfterVehicleAndPolicyChanges() {
        String vehicle = activeVehicle();
        var old = create.create(command(vehicle));
        assertTrue(probe.transactionActive);
        assertEquals(2_200_000, old.price().rentalAmount());
        assertEquals(Optional.of(old), actualReads.findByCode(old.code())); // Đọc ngoài giao dịch đã commit.
        assertTrue(vehicleWrites.updateDailyRate(vehicle, new DailyRate(2_000_000)));
        var base = policy.value;
        policy.value = new PricingParameters(base.weekendDays(), new BigDecimal("0.4"), new BigDecimal("0.8"),
                base.holidayCalendar(), new InsuranceTerms(InsuranceCalculation.PERCENT_OF_RENT,
                new BigDecimal("0.1")), Duration.ofMinutes(30));
        var fresh = create.create(command(vehicle));
        assertNotEquals(old.code(), fresh.code());
        assertEquals(4_800_000, fresh.price().rentalAmount());
        assertEquals(480_000, fresh.price().insuranceFee());
        assertEquals(Duration.ofMinutes(30), Duration.between(fresh.createdAt(), fresh.expiresAt()));
        assertEquals(old, get.get(GetQuoteQuery.from(old.code())));
        assertEquals(1_000_000, old.snapshot().dailyRate());
    }

    /** Hết hạn vẫn đọc đúng snapshot, không từ chối QUOTE_EXPIRED tại GET. */
    @Test
    void readsExpiredQuoteFromDatabaseWithoutRepricing() {
        var original = create.create(command(activeVehicle()));
        clock.at = original.expiresAt().plusSeconds(1);
        assertEquals(original, get.get(GetQuoteQuery.from(original.code())));
    }

    /** Đọc lại sau khi chèn lỗi phải rollback cả header và các dòng, không chỉ rollback object Java. */
    @Test
    void reloadFailureRollsBackHeaderAndLines() {
        String vehicle = activeVehicle();
        long before = lineCount();
        probe.fail = true;
        assertThrowsExactly(IllegalStateException.class, () -> create.create(command(vehicle)));
        assertNotNull(probe.last);
        assertEquals(2, probe.last.price().lines().size()); // Đã quan sát các dòng trong transaction trước khi gây lỗi.
        assertTrue(probe.transactionActive);
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertTrue(actualReads.findByCode(probe.last.code()).isEmpty());
        assertEquals(before, lineCount());
    }

    /** Lỗi CHECK ở dòng thứ hai rollback header và dòng thứ nhất; giữ đúng SQLSTATE/tên constraint. */
    @Test
    void lineFailureRollsBackEarlierRows() {
        long before = lineCount();
        var original = quote("BG-2611-BAD001", Duration.ofMinutes(15));
        var lines = new ArrayList<>(original.price().lines());
        var second = lines.get(1);
        lines.set(1, new QuoteLine(second.type(), second.dayType(), second.startAt(), 25,
                second.dailyRate(), second.uplift(), second.amount()));
        var bad = new Quote(original.code(), original.vehicleCode(), original.startAt(), original.endAt(),
                original.rentalType(), original.driveMode(), original.pickupMethod(), original.snapshot(),
                new CalculatedPrice(lines, original.price().rentalAmount(), original.price().insuranceFee(),
                        original.price().totalAmount()), original.createdAt(), original.expiresAt());
        var failure = assertThrows(DataIntegrityViolationException.class,
                () -> new TransactionTemplate(transactionManager).execute(status -> quoteWrites.insert(bad)));
        var cause = assertInstanceOf(PSQLException.class, failure.getMostSpecificCause());
        assertEquals("23514", cause.getSQLState());
        assertEquals("chk_quote_line_type_hours", cause.getServerErrorMessage().getConstraint());
        assertTrue(actualReads.findByCode(bad.code()).isEmpty());
        assertEquals(before, lineCount());
    }

    /** Tạo danh mục xe thật trong schema vehicle; không cần chi nhánh thật vì price không ghép chi nhánh. */
    private String activeVehicle() {
        String code = codes.generate("XE");
        assertTrue(vehicleWrites.insert(Vehicle.restore(code, "QUOTE-" + code, OwnershipType.COMPANY,
                FuelType.PETROL, 42L, VehicleStatus.ACTIVE,
                new VehicleDocuments(LocalDate.of(2030, 1, 1), LocalDate.of(2030, 1, 1)),
                SPECIFICATIONS, new DailyRate(1_000_000))));
        return code;
    }

    /** Khoảng hai khối theo ví dụ a, dùng chung validation thật của booking. */
    private static CreateQuoteCommand command(String code) {
        return new CreateQuoteCommand(code, at("2026-11-13T10:00"), at("2026-11-15T10:00"),
                RentalType.DAILY, DriveMode.SELF_DRIVE, PickupMethod.BRANCH);
    }

    /** Đếm độc lập sau commit/rollback, SQL vẫn nằm trong tài nguyên test. */
    private long lineCount() { return jdbc.getJdbcTemplate().queryForObject(loader.load("sql/pricing/count_quote_lines.sql"), Long.class); }

    /** Nguồn điều khiển chỉ ở context test riêng, không thêm cờ vào code production. */
    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        /** Dùng cùng Clock cho booking và pricing để điều kiện thuê tái lập được. */
        @Bean @Primary MutableClock quoteTestClock() { return new MutableClock(); }
        /** Thay nguồn cấu hình qua đúng port, mô phỏng một phiên bản mới mà không sửa báo giá cũ. */
        @Bean @Primary MutablePolicy quoteTestPolicy() { return new MutablePolicy(); }
        /** Đọc adapter thật trước khi mô phỏng lỗi reload. */
        @Bean @Primary ReadProbe quoteReadProbe(@Qualifier("quoteReadAdapter") ReadQuotePort delegate) { return new ReadProbe(delegate); }
    }

    /** Clock điều khiển được, luôn dùng giờ Việt Nam. */
    static class MutableClock extends Clock {
        Instant at = CREATED_AT;
        /** Múi giờ dùng chung của test. */
        @Override public ZoneId getZone() { return ZONE; }
        /** Trả Clock tại cùng mốc với múi giờ yêu cầu. */
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(at, zone); }
        /** Mốc do từng test đặt, không lấy thời gian máy. */
        @Override public Instant instant() { return at; }
    }

    /** Nguồn tham số thay được chỉ trong test. */
    static class MutablePolicy implements ReadPricingPolicyPort {
        PricingParameters value = parameters();
        /** Trả phiên bản hiện tại do test chọn, giữ nguyên hợp đồng mốc at. */
        @Override public PricingParameters parametersAt(Instant at) { assertNotNull(at); return value; }
    }

    /** Bọc cổng đọc để quan sát dữ liệu chưa commit và giao dịch hiện tại. */
    static class ReadProbe implements ReadQuotePort {
        private final ReadQuotePort delegate;
        boolean fail;
        boolean transactionActive;
        QuoteDetail last;
        /** Nhận adapter thật, không mở transaction riêng. */
        ReadProbe(ReadQuotePort delegate) { this.delegate = delegate; }
        /** Gây lỗi sau khi đã đọc cả header/dòng để chứng minh rollback CSDL. */
        @Override public Optional<QuoteDetail> findByCode(String code) {
            transactionActive = TransactionSynchronizationManager.isActualTransactionActive();
            var result = delegate.findByCode(code);
            last = result.orElse(null);
            if (fail) throw new IllegalStateException("Simulated quote reload failure.");
            return result;
        }
    }
}
