package com.carrental.pricing.adapter.out.persistence;

import com.carrental.PostgresTestConfiguration;
import com.carrental.pricing.application.port.out.ReadQuotePort;
import com.carrental.pricing.application.port.out.WriteQuotePort;
import com.carrental.pricing.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import static com.carrental.pricing.domain.QuoteTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Kiểm JDBC/SQL/V009 trên PostgreSQL thật; dữ liệu thử rollback, không đụng CSDL local. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
@Transactional
class QuotePersistenceIntegrationTest {
    @Autowired private WriteQuotePort writes;
    @Autowired private ReadQuotePort reads;

    /** Giá, từng dòng, snapshot và hạn round-trip đúng, kể cả hạn dương nhỏ hơn độ chính xác timestamp PostgreSQL. */
    @ParameterizedTest
    @ValueSource(strings = {"PT15M", "PT0.5S", "PT0.000000001S"})
    void roundTripsCompleteSnapshotAndExactExpiry(String duration) {
        var original = quote("BG-2611-ROUND1", Duration.parse(duration));
        assertTrue(writes.insert(original));
        var saved = reads.findByCode(original.code()).orElseThrow();
        assertEquals(detail(original), saved);
        assertEquals(saved.createdAt().plus(Duration.parse(duration)), saved.expiresAt());
        assertEquals(2, saved.price().lines().size());
        assertEquals(2_400_000, saved.price().totalAmount());
    }

    /** Trùng mã không ghi lại header hoặc thêm dòng; return false chỉ cho uq_quote_code. */
    @Test
    void duplicateCodeDoesNotOverwriteOrAppendLines() {
        var original = quote("BG-2611-DUP001", Duration.ofMinutes(15));
        assertTrue(writes.insert(original));
        var duplicate = quote(original.code(), Duration.ofMinutes(30));
        assertFalse(writes.insert(duplicate));
        assertEquals(detail(original), reads.findByCode(original.code()).orElseThrow());
    }

    /** Mã thiếu trả Optional rỗng, không tự tính báo giá từ nguồn hiện tại. */
    @Test
    void missingCodeReturnsEmpty() {
        assertTrue(reads.findByCode("missing-quote").isEmpty());
    }
}
