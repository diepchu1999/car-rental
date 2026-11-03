package com.carrental.identity.adapter.out.persistence;

import com.carrental.identity.application.port.out.DocumentNumberCryptoPort;
import com.carrental.identity.domain.DocumentNumber;
import com.carrental.identity.domain.DocumentProtectionContext;
import com.carrental.identity.domain.DocumentType;
import com.carrental.shared.sql.SensitiveSql;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import java.sql.Types;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Fixture PostgreSQL cho V012, dùng số hoàn toàn giả và adapter mật mã thật trên test classpath. */
abstract class DocumentDatabaseTestSupport extends IdentityDatabaseTestSupport {
    private static final AtomicLong DOCUMENT_SEQUENCE = new AtomicLong();
    static final String FAKE_NUMBER = "000000000000";
    @Autowired DocumentNumberCryptoPort crypto;

    /** Tạo lượt tải đúng chủ/loại/mặt; TTL mẫu không thay thế cấu hình của ứng dụng. */
    MapSqlParameterSource upload(long owner, String type, String side) {
        return new MapSqlParameterSource().addValue("code", nextCode("AN"), Types.VARCHAR)
                .addValue("accountId", owner).addValue("type", type).addValue("side", side)
                .addValue("objectKey", "incoming/" + UUID.randomUUID())
                .addValue("createdAt", AT).addValue("expiresAt", "2026-10-13T03:05:00Z");
    }

    /** Tạo bản nộp với AAD đúng chủ và mã GT; không giữ số rõ trong tham số SQL. */
    MapSqlParameterSource submission(long owner, String type, String status) {
        return submission(owner, type, status, fakeNumber(accountCode(owner)));
    }

    /** Chỉ các test tranh số truyền cùng số tường minh; mặc định mỗi tài khoản dùng số giả riêng. */
    MapSqlParameterSource submission(long owner, String type, String status, String plaintext) {
        String code = nextCode("GT");
        var context = new DocumentProtectionContext(accountCode(owner), DocumentType.valueOf(type), code);
        var number = crypto.protect(new DocumentNumber(plaintext), context);
        boolean card = type.equals("IDENTITY_CARD");
        boolean reviewed = status.equals("VERIFIED") || status.equals("REJECTED");
        return new MapSqlParameterSource().addValue("code", code).addValue("accountId", owner)
                .addValue("type", type).addValue("status", status).addValue("keyVersion", number.keyVersion())
                .addValue("nonce", number.nonceBase64()).addValue("ciphertext", number.ciphertextBase64())
                .addValue("hash", number.lookupHash())
                .addValue("fullName", card ? "LOCAL FAKE DOCUMENT" : null, Types.VARCHAR)
                .addValue("birthDate", card ? "1990-01-01" : null, Types.VARCHAR)
                .addValue("expiresOn", "2030-01-01", Types.VARCHAR)
                .addValue("issuedOn", card ? null : "2025-01-01", Types.VARCHAR)
                .addValue("licenseClass", card ? null : "B", Types.VARCHAR)
                .addValue("automaticOnly", card ? null : false, Types.BOOLEAN)
                .addValue("submittedAt", AT).addValue("reviewedAt", reviewed ? AT : null, Types.VARCHAR)
                .addValue("reviewerId", reviewed ? newAccount() : null, Types.BIGINT)
                .addValue("rejectionCode", status.equals("REJECTED") ? "ILLEGIBLE" : null, Types.VARCHAR)
                .addValue("rejectionNote", null, Types.VARCHAR);
    }

    /** Liên kết ảnh giả đã chốt; metadata phải khớp các quy tắc cấu trúc ở CSDL. */
    MapSqlParameterSource image(long document, long upload, long owner, String type, String side) {
        return new MapSqlParameterSource().addValue("submissionId", document).addValue("uploadId", upload)
                .addValue("accountId", owner).addValue("type", type).addValue("side", side)
                .addValue("objectKey", "sealed/" + UUID.randomUUID()).addValue("contentType", "image/png")
                .addValue("sizeBytes", 100L).addValue("width", 10).addValue("height", 10);
    }

    /** Hai con trỏ được truyền tường minh; null active biểu diễn chưa có bản xác minh. */
    MapSqlParameterSource head(long owner, String type, Long latest, Long active) {
        return new MapSqlParameterSource().addValue("accountId", owner).addValue("type", type)
                .addValue("latestId", latest, Types.BIGINT).addValue("activeId", active, Types.BIGINT);
    }

    /** Chạy file fixture riêng, không nối input thành câu SQL. */
    long insert(String resource, MapSqlParameterSource parameters) {
        if (resource.equals("submission") && "IDENTITY_CARD".equals(parameters.getValue("type"))
                && "VERIFIED".equals(parameters.getValue("status"))) {
            // Fixture lịch sử hợp lệ phải có claim như schema V013; test âm dùng JDBC thẳng để bỏ qua.
            SensitiveSql.execute(() -> jdbc.update(documentSql("register_identity_card_fixture"), parameters));
        }
        Long id = jdbc.queryForObject(documentSql("insert_" + resource), parameters, Long.class);
        assertNotNull(id);
        return id;
    }

    /** Dùng SqlLoader như code thật; không hardcode SQL trong test Java. */
    String documentSql(String name) {
        return sqlLoader.load("sql/identity/documents/" + name + ".sql");
    }

    /** Lấy mã để tạo AAD; chỉ dữ liệu trong container test, không in số điện thoại từ row. */
    String accountCode(long owner) {
        return jdbc.queryForObject(sqlLoader.load("sql/identity/read_account_for_test.sql"),
                Map.of("id", owner), (row, ignored) -> row.getString("code"));
    }

    /** Số chỉ dùng trong test, phân biệt chủ và giữ ổn định qua các phiên bản của cùng chủ. */
    static String fakeNumber(String ownerCode) { return "LOCAL-FAKE-NUMBER-" + ownerCode; }

    /** Sinh mã fixture sáu ký tự khác nhau giữa test; không thay thế BusinessCodeGenerator production. */
    private static String nextCode(String prefix) {
        return "%s-D%05d".formatted(prefix, DOCUMENT_SEQUENCE.incrementAndGet());
    }
}
