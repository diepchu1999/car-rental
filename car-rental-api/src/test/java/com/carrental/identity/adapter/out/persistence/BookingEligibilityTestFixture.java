package com.carrental.identity.adapter.out.persistence;

import com.carrental.shared.sql.SensitiveSql;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Chỉ dựng metadata giấy tờ giả trong PostgreSQL test cho luồng HTTP booking.
 * Cổng điều kiện thuê vẫn đọc adapter/domain thật; luồng tải ảnh và xác minh đã có test riêng ở identity.
 */
public class BookingEligibilityTestFixture extends DocumentDatabaseTestSupport {
    private static final AtomicLong REVIEWERS = new AtomicLong();
    @Autowired private PlatformTransactionManager transactions;

    /** Hai bản VERIFIED có reviewer khác chủ; commit trước khi request booking bắt đầu. */
    public void verified(String accountCode, String licenseClass, String issuedOn) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> SensitiveSql.execute(() -> {
            Long owner = jdbc.queryForObject(documentSql("find_account_id_by_code"), Map.of("code", accountCode), Long.class);
            if (owner == null) throw new IllegalStateException("Test account was not initialized through HTTP.");
            var card = submission(owner, "IDENTITY_CARD", "VERIFIED");
            long cardId = insert("submission", card);
            insert("head", head(owner, "IDENTITY_CARD", cardId, cardId));
            var license = submission(owner, "DRIVER_LICENSE", "VERIFIED")
                    .addValue("licenseClass", licenseClass).addValue("issuedOn", issuedOn, java.sql.Types.VARCHAR);
            long licenseId = insert("submission", license);
            insert("head", head(owner, "DRIVER_LICENSE", licenseId, licenseId));
            return null;
        }));
    }

    /** Reviewer dùng dải dữ liệu giả riêng, không trùng ADMIN/CUSTOMER của TestJwtIssuer. */
    @Override
    long newAccount() {
        long sequence = REVIEWERS.incrementAndGet();
        return insertAccount(accountParameters().addValue("code", "TK-J%05d".formatted(sequence))
                .addValue("subject", "booking-reviewer-" + UUID.randomUUID())
                .addValue("phone", "+8494%07d".formatted(sequence)));
    }
}
