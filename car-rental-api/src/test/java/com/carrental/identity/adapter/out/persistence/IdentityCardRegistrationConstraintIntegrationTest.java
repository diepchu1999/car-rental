package com.carrental.identity.adapter.out.persistence;

import com.carrental.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** BR-130: SQL thẳng vẫn bị UNIQUE/FK chặn; không dùng adapter để tự chứng minh ràng buộc của nó. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
@Transactional
class IdentityCardRegistrationConstraintIntegrationTest extends DocumentDatabaseTestSupport {
    @Autowired PlatformTransactionManager transactions;

    /** Hai chủ cùng hash bị đúng UNIQUE, không phân vùng theo phiên bản khoá để né tranh chấp. */
    @Test void rejectsSecondOwnerForSameHash() {
        String number = "LOCAL-" + UUID.randomUUID();
        var first = submission(newAccount(), "IDENTITY_CARD", "PENDING", number);
        var second = submission(newAccount(), "IDENTITY_CARD", "PENDING", number).addValue("keyVersion", 2);
        assertEquals(first.getValue("hash"), second.getValue("hash"));
        jdbc.update(documentSql("insert_registration_for_test"), first);
        assertConstraint(() -> jdbc.update(documentSql("insert_registration_for_test"), second),
                "23505", "identity_card_registration", "uq_identity_card_registration_hash");
    }

    /** Đổi status bằng SQL cũng không được VERIFIED nếu chưa đăng ký số đúng chủ. */
    @Test void cannotMarkVerifiedWithoutRegistration() {
        long owner = newAccount();
        var pending = submission(owner, "IDENTITY_CARD", "PENDING");
        long id = insert("submission", pending);
        var review = submission(owner, "IDENTITY_CARD", "VERIFIED").addValue("id", id);
        assertConstraint(() -> jdbc.update(documentSql("update_review"), review),
                "23503", "document_submission", "fk_document_verified_identity_registration");
    }

    /** Có hash đã đăng ký nhưng thuộc chủ khác vẫn không vượt được FK ghép. */
    @Test void cannotMarkVerifiedWithAnotherOwnersRegistration() {
        String number = "LOCAL-" + UUID.randomUUID(); long owner = newAccount();
        var first = submission(owner, "IDENTITY_CARD", "VERIFIED", number);
        insert("submission", first);
        long stranger = newAccount(); var pending = submission(stranger, "IDENTITY_CARD", "PENDING", number);
        long id = insert("submission", pending);
        var review = submission(stranger, "IDENTITY_CARD", "VERIFIED", number).addValue("id", id);
        assertConstraint(() -> jdbc.update(documentSql("update_review"), review),
                "23503", "document_submission", "fk_document_verified_identity_registration");
    }

    /** Cùng chủ giữ nhiều phiên bản VERIFIED hợp lệ nhưng chỉ một đăng ký số. */
    @Test void allowsVerifiedHistoryForSameOwner() {
        long owner = newAccount();
        var first = submission(owner, "IDENTITY_CARD", "VERIFIED");
        var second = submission(owner, "IDENTITY_CARD", "VERIFIED");
        insert("submission", first); insert("submission", second);
        assertEquals(List.of(accountCode(owner)), jdbc.queryForList(documentSql("read_registration_for_test"),
                Map.of("code", first.getValue("code")), String.class));
    }

    /** Insert VERIFIED thẳng cũng bị FK, không chỉ UPDATE status mới có bảo vệ. */
    @Test void cannotInsertVerifiedWithoutRegistration() {
        var values = submission(newAccount(), "IDENTITY_CARD", "VERIFIED");
        assertConstraint(() -> jdbc.queryForObject(documentSql("insert_submission"), values, Long.class),
                "23503", "document_submission", "fk_document_verified_identity_registration");
    }

    /** Hash sai shape/null phải có lỗi đúng nguyên nhân tại bảng đăng ký, không lưu giá trị giả. */
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"wrong", "AABB", " ", "012345"})
    void rejectsInvalidHash(String hash) {
        var values = new MapSqlParameterSource().addValue("hash", hash).addValue("accountId", newAccount());
        if (hash == null) assertNotNullColumn(() -> jdbc.update(documentSql("insert_registration_for_test"), values), "identity_card_registration", "number_hash");
        else assertConstraint(() -> jdbc.update(documentSql("insert_registration_for_test"), values), "23514", "identity_card_registration", "chk_identity_card_registration_hash");
    }

    /** Đăng ký không được tham chiếu tài khoản chưa tồn tại. */
    @Test void rejectsUnknownAccount() {
        var values = new MapSqlParameterSource().addValue("hash", "a".repeat(64)).addValue("accountId", -1L);
        assertConstraint(() -> jdbc.update(documentSql("insert_registration_for_test"), values), "23503", "identity_card_registration", "fk_identity_card_registration_account");
    }

    /** Hai INSERT thật tranh UNIQUE: một commit, một 23505 đúng tên; không phụ thuộc kiểm trước của adapter. */
    @Test @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentRegistrationsHaveExactlyOneOwner() throws Exception {
        String number = "LOCAL-" + UUID.randomUUID();
        var first = submission(newAccount(), "IDENTITY_CARD", "PENDING", number);
        var second = submission(newAccount(), "IDENTITY_CARD", "PENDING", number);
        insert("submission", first); insert("submission", second);
        // Phép thử đảo chỉ trên container test: bỏ comment dòng sau, đúng một assertion phải đỏ.
        // jdbc.getJdbcTemplate().execute("ALTER TABLE identity.identity_card_registration DROP CONSTRAINT uq_identity_card_registration_hash");
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> attempt(first, ready, start));
            var b = executor.submit(() -> attempt(second, ready, start));
            try { assertTrue(ready.await(10, TimeUnit.SECONDS)); } finally { start.countDown(); }
            assertEquals(1, List.of(a.get(25, TimeUnit.SECONDS), b.get(25, TimeUnit.SECONDS)).stream().filter(Boolean::booleanValue).count());
        }
        var owners = jdbc.queryForList(documentSql("read_registration_for_test"), Map.of("code", first.getValue("code")), String.class);
        assertEquals(1, owners.size());
        assertTrue(List.of(accountCode((Long) first.getValue("accountId")), accountCode((Long) second.getValue("accountId"))).contains(owners.getFirst()));
    }

    /** Chỉ SQLSTATE 23505 đúng constraint mới là thua; timeout/deadlock đều làm test lỗi. */
    private boolean attempt(MapSqlParameterSource values, CountDownLatch ready, CountDownLatch start) {
        var tx = new TransactionTemplate(transactions); tx.setTimeout(15);
        try {
            tx.executeWithoutResult(status -> {
                ready.countDown();
                try { if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("Race start timed out."); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
                jdbc.update(documentSql("insert_registration_for_test"), values);
            }); return true;
        } catch (DataIntegrityViolationException failure) {
            var postgres = assertInstanceOf(PSQLException.class, failure.getMostSpecificCause());
            assertEquals("23505", postgres.getSQLState());
            assertNotNull(postgres.getServerErrorMessage());
            assertEquals("uq_identity_card_registration_hash", postgres.getServerErrorMessage().getConstraint());
            return false;
        }
    }
}
