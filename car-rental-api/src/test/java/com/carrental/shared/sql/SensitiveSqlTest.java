package com.carrental.shared.sql;

import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataIntegrityViolationException;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Security-guideline §3: phạm vi che log phải lồng được, không rò thread và không giữ lỗi thô. */
class SensitiveSqlTest {
    /** Kết quả không đổi; ra khỏi phạm vi lồng trong vẫn giữ phạm vi ngoài rồi dọn sạch. */
    @Test
    void restoresNestedScopesAndReturnsUnchangedResult() {
        Object value = new Object();
        assertFalse(SensitiveSql.isActive());
        assertSame(value, SensitiveSql.execute(() -> {
            assertTrue(SensitiveSql.isActive());
            assertSame(value, SensitiveSql.execute(() -> value));
            assertTrue(SensitiveSql.isActive());
            return value;
        }));
        assertFalse(SensitiveSql.isActive());
    }

    /** Detail, SQL, cause, suppressed và nextException đều không đi theo exception đã che. */
    @Test
    void preservesOnlySafeDatabaseMetadataAndOriginalStack() {
        var postgres = new PSQLException(new ServerErrorMessage(
                "SERROR\0C23505\0Msecret-number\0Dsecret-object-key\0nuq_document_upload_object\0\0"));
        postgres.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("com.carrental.identity.adapter.out.persistence.DocumentUploadWriteAdapter",
                        "insert", "DocumentUploadWriteAdapter.java", 41)
        });
        postgres.setNextException(new java.sql.SQLException("secret-next"));
        postgres.addSuppressed(new IllegalArgumentException("secret-suppressed"));
        var original = new DataIntegrityViolationException("secret-expanded-sql", postgres);
        SensitiveSqlFailure safe = assertThrows(SensitiveSqlFailure.class, () -> SensitiveSql.execute(() -> {
            throw original;
        }));
        assertEquals("23505", safe.sqlState());
        assertEquals("uq_document_upload_object", safe.constraint());
        assertArrayEquals(postgres.getStackTrace(), safe.getStackTrace());
        assertNull(safe.getCause());
        assertEquals(0, safe.getSuppressed().length);
        var text = new StringWriter();
        safe.printStackTrace(new PrintWriter(text));
        assertFalse(text.toString().contains("secret-"));
        assertTrue(text.toString().contains("PSQLException"));
        assertEquals("secret-expanded-sql", original.getMessage());
        assertFalse(SensitiveSql.isActive());
    }

    /** Mapper lỗi cũng được che; nested scope không bọc lại exception đã an toàn. */
    @Test
    void sanitizesMapperFailureAndRestoresOuterScope() {
        SensitiveSql.execute(() -> {
            var safe = assertThrows(SensitiveSqlFailure.class, () -> SensitiveSql.execute(() -> {
                throw new IllegalArgumentException("secret-enum-or-row-value");
            }));
            assertEquals("-", safe.sqlState());
            assertFalse(safe.getMessage().contains("secret-"));
            assertTrue(SensitiveSql.isActive());
            assertSame(safe, assertThrows(SensitiveSqlFailure.class,
                    () -> SensitiveSql.execute(() -> { throw safe; })));
            return null;
        });
        assertFalse(SensitiveSql.isActive());
    }

    /** Hai thread chạy đồng thời không dùng chung cờ; có timeout để test không treo khi lỗi. */
    @Test
    void isolatesConcurrentThreads() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var sensitive = executor.submit(() -> SensitiveSql.execute(() -> {
                entered.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted test.");
                }
                return SensitiveSql.isActive();
            }));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertFalse(executor.submit(SensitiveSql::isActive).get(5, TimeUnit.SECONDS));
            } finally { release.countDown(); }
            assertTrue(sensitive.get(5, TimeUnit.SECONDS));
        }
        assertFalse(SensitiveSql.isActive());
    }

    /** Metadata lạ bị bỏ thay vì trở thành đường chèn dòng giả vào log. */
    @Test
    void rejectsUnsafeConstraintMetadata() {
        var postgres = new PSQLException(new ServerErrorMessage("SERROR\0C23505\0Mhidden\0nunsafe\nvalue\0\0"));
        assertEquals("-", SensitiveSqlFailure.from(postgres).constraint());
    }
}
