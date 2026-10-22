package com.carrental.keycloak.otp;

import com.carrental.keycloak.support.KeycloakOtpContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** BR-129: chạy đúng JAR trên Keycloak/PostgreSQL thật; không mock transaction, khoá hay counter. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KeycloakOtpIT {
    private final AtomicInteger sequence = new AtomicInteger(1000);
    private final KeycloakOtpContainer server = new KeycloakOtpContainer();

    /** Không bỏ qua test nếu Docker chưa được cho phép chạy. */
    @BeforeAll void start() { server.start(); }
    /** Chỉ dọn container cô lập của lớp test. */
    @AfterAll void stop() { server.close(); }

    /** Cài nhầm local-sms vào server thường phải chặn ngay lúc startup, đúng lý do SMS giả. */
    @Test void rejectsLocalSmsInNormalKeycloakServer() { server.assertNormalStartRejectsLocalSms(); }

    /** Chuẩn hoá số, mã đủ sáu chữ số, hiệu lực đúng năm phút và không lưu OTP bản rõ. */
    @Test void storesDigestWithConfiguredLifetimeAndNormalizedPhone() throws Exception {
        var user = user(); var issued = issue(user); String code = server.code(issued.id());
        try (var connection = server.connection(); var query = sql(connection, "inspect_challenge", issued.id());
             var rows = query.executeQuery()) {
            assertTrue(rows.next()); assertEquals(300, rows.getInt("lifetime_seconds"));
            assertTrue(rows.getString("code_digest").matches("[a-f0-9]{64}"));
            assertNotEquals(code, rows.getString("code_digest"));
            assertNotEquals(user.get("phone"), rows.getString("phone_key"));
        }
        var national = changed(user, "phone", "0" + user.get("phone").substring(3));
        assertEquals("VERIFIED", verify(national, issued.id(), code));
        assertEquals("CONSUMED", verify(user, issued.id(), code));
    }

    /** Mã bị ràng buộc vào số, phiên và mục đích; thử sai phạm vi không tiêu thụ mã đúng. */
    @Test void bindsChallengeToPhoneSessionAndPurpose() throws Exception {
        var user = user(); var issued = issue(user); String code = server.code(issued.id());
        assertEquals("INVALID", verify(changed(user, "session", UUID.randomUUID().toString()), issued.id(), code));
        assertEquals("INVALID", verify(changed(user, "phone", user().get("phone")), issued.id(), code));
        assertEquals("INVALID", verify(changed(user, "purpose", "EMPLOYEE_LOGIN"), issued.id(), code));
        assertEquals("VERIFIED", verify(user, issued.id(), code));
    }

    /** Sai năm lần huỷ mã; nhập đúng sau đó cũng không được hồi sinh mã. */
    @Test void exhaustsChallengeAfterFiveWrongAttempts() throws Exception {
        var user = user(); var issued = issue(user);
        for (int attempt = 1; attempt <= 5; attempt++)
            assertEquals(attempt == 5 ? "ATTEMPTS_EXHAUSTED" : "INVALID", verify(user, issued.id(), "not-a-code"));
        assertEquals("ATTEMPTS_EXHAUSTED", verify(user, issued.id(), server.code(issued.id())));
        assertEquals(5, scalar("failed_events", issued.id()));
    }

    /** Quá thời hạn không được dùng ngay cả khi sáu chữ số đúng. */
    @Test void rejectsExpiredCode() throws Exception {
        var user = user(); var issued = issue(user);
        update("expire_challenge", issued.id());
        assertEquals("EXPIRED", verify(user, issued.id(), server.code(issued.id())));
    }

    /** Nhịp 60 giây dùng chung cho các phiên; gửi lại sau hạn vô hiệu mã trước. */
    @Test void enforcesResendDelayAndSupersedesEarlierCode() throws Exception {
        var user = user(); var first = issue(user);
        var otherTab = changed(user, "session", UUID.randomUUID().toString());
        assertEquals("RESEND_TOO_SOON", send(otherTab).result());
        update("age_sends", 61, first.id());
        var second = issue(otherTab);
        assertEquals("INVALID", verify(user, first.id(), server.code(first.id())));
        assertEquals("VERIFIED", verify(otherTab, second.id(), server.code(second.id())));
    }

    /** Cửa sổ 60 phút theo số vẫn chặn sau 59 phút; qua 60 phút mới được gửi lại. */
    @Test void limitsPhoneInRollingHour() throws Exception {
        var user = user(); var first = issue(user);
        update("age_sends", 3540, first.id()); seed(first.id(), "SENT", 3540, 4);
        assertEquals("PHONE_RATE_LIMITED", send(user).result());
        update("age_sends", 3601, first.id());
        assertEquals("SENT", send(user).result());
    }

    /** Hạn mức ngày không mất khi qua giờ mới; chỉ mở sau cửa sổ trượt 24 giờ. */
    @Test void limitsPhoneInRollingDay() throws Exception {
        var user = user(); var first = issue(user);
        update("age_sends", 7200, first.id()); seed(first.id(), "SENT", 7200, 9);
        assertEquals("PHONE_RATE_LIMITED", send(user).result());
        update("age_sends", 86401, first.id());
        assertEquals("SENT", send(user).result());
    }

    /** Cùng IP gửi cho số khác vẫn chịu chung hạn mức 20 lần mỗi 60 phút. */
    @Test void limitsIpSendsAcrossDifferentPhones() throws Exception {
        var firstUser = user(); var first = issue(firstUser); seed(first.id(), "SENT", 120, 19);
        var secondUser = changed(user(), "ip", firstUser.get("ip"));
        assertEquals("IP_RATE_LIMITED", send(secondUser).result());
        update("age_sends", 3601, first.id());
        assertEquals("SENT", send(secondUser).result());
    }

    /** Sai 100 lần, kể cả challenge không tồn tại, chặn xác minh và phát mã trên cùng IP. */
    @Test void limitsOneHundredWrongAttemptsPerIpAcrossSessions() throws Exception {
        var user = user(); var issued = issue(user);
        for (int attempt = 0; attempt < 100; attempt++)
            assertEquals("INVALID", verify(changed(user, "session", UUID.randomUUID().toString()), UUID.randomUUID().toString(), "000000"));
        assertEquals(100, scalar("failed_events", issued.id()));
        assertEquals("IP_RATE_LIMITED", verify(user, issued.id(), server.code(issued.id())));
        assertEquals("IP_RATE_LIMITED", send(changed(user(), "ip", user.get("ip"))).result());
    }

    /** Không được lách giới hạn bằng cách viết IPv6 rút gọn/đầy đủ khác nhau. */
    @Test void normalizesIpv6BeforeCounting() throws Exception {
        var user = changed(user(), "ip", "2001:db8::1234"); var issued = issue(user);
        seed(issued.id(), "FAILED", 120, 100);
        assertEquals("IP_RATE_LIMITED", verify(changed(user, "ip", "2001:0db8:0:0:0:0:0:1234"), issued.id(), server.code(issued.id())));
    }

    /** Sự kiện sai quá 60 phút không còn chặn; không phải bộ đếm vĩnh viễn hay giờ tròn. */
    @Test void expiresIpFailuresWithRollingWindow() throws Exception {
        var user = user(); var issued = issue(user); seed(issued.id(), "FAILED", 3601, 100);
        assertEquals("VERIFIED", verify(user, issued.id(), server.code(issued.id())));
    }

    /** Transaction cha rollback không làm mất lần sai, cũng không hồi sinh OTP vừa dùng. */
    @Test void commitsIndependentlyOfOuterLoginRollback() throws Exception {
        var user = user(); var issued = issue(changed(user, "rollback", "true"));
        var rollback = changed(user, "rollback", "true");
        assertEquals("INVALID", verify(rollback, issued.id(), "not-a-code"));
        assertEquals(1, scalar("failed_events", issued.id()));
        assertEquals("VERIFIED", verify(rollback, issued.id(), server.code(issued.id())));
        assertEquals("CONSUMED", verify(user, issued.id(), server.code(issued.id())));
    }

    /** Hai HTTP request cùng dùng mã: chính xác một thành công, bên kia bị từ chối có nghĩa, không 500. */
    @Test void consumesOnceUnderDeterministicRace() throws Exception {
        var user = user(); var issued = issue(user); String code = server.code(issued.id());
        try (var blocker = server.connection(); var pool = Executors.newFixedThreadPool(2)) {
            blocker.setAutoCommit(false);
            try (var lock = sql(blocker, "lock_challenge", issued.id()); var rows = lock.executeQuery()) { assertTrue(rows.next()); }
            var first = pool.submit(() -> verify(user, issued.id(), code));
            var second = pool.submit(() -> verify(user, issued.id(), code));
            try { awaitTwoBlockedTransactions(); }
            finally { blocker.rollback(); }
            var outcomes = new ArrayList<>(List.of(first.get(50, TimeUnit.SECONDS), second.get(50, TimeUnit.SECONDS)));
            outcomes.sort(String::compareTo);
            assertEquals(List.of("CONSUMED", "VERIFIED"), outcomes);
        }
    }

    /** Lặp hai luồng trên 20 mã/số/IP riêng, không chia sẻ kết quả trong bộ nhớ server. */
    @Test void consumesOnceAcrossRepeatedConcurrentRequests() throws Exception {
        for (int repetition = 0; repetition < 20; repetition++) {
            var user = user(); var issued = issue(user); String code = server.code(issued.id());
            assertEquals(List.of("CONSUMED", "VERIFIED"), race(() -> verify(user, issued.id(), code), () -> verify(user, issued.id(), code)));
        }
    }

    /** Hai phiên xin mã cùng số, khác IP: chỉ một SMS được phép phát. */
    @Test void serializesConcurrentSendsForSamePhone() throws Exception {
        var first = user(); var second = changed(user(), "phone", first.get("phone"));
        assertEquals(List.of("RESEND_TOO_SOON", "SENT"), race(() -> send(first).result(), () -> send(second).result()));
    }

    /** Còn đúng một lượt IP: hai số gửi song song không thể cùng chiếm lượt đó. */
    @Test void serializesConcurrentSendsAtIpLimit() throws Exception {
        var source = user(); var issued = issue(source); seed(issued.id(), "SENT", 120, 18);
        var first = changed(user(), "ip", source.get("ip")); var second = changed(user(), "ip", source.get("ip"));
        assertEquals(List.of("IP_RATE_LIMITED", "SENT"), race(() -> send(first).result(), () -> send(second).result()));
    }

    /** Còn một lần nhập sai theo IP: hai luồng chỉ một được ghi nhận, bộ đếm dừng đúng 100. */
    @Test void serializesConcurrentFailuresAtIpLimit() throws Exception {
        var user = user(); var issued = issue(user); seed(issued.id(), "FAILED", 120, 99);
        assertEquals(List.of("INVALID", "IP_RATE_LIMITED"), race(
                () -> verify(user, UUID.randomUUID().toString(), "000000"),
                () -> verify(user, UUID.randomUUID().toString(), "000000")));
        assertEquals(100, scalar("failed_events", issued.id()));
    }

    /** Restart server không reset hạn mức/trạng thái, và Liquibase không chạy lại CREATE SCHEMA. */
    @Test void preservesConsumptionFailuresAndLimitsAcrossServerRestart() throws Exception {
        var consumed = user(); var first = issue(consumed); String consumedCode = server.code(first.id());
        assertEquals("VERIFIED", verify(consumed, first.id(), consumedCode)); seed(first.id(), "SENT", 120, 4);
        var pending = user(); var second = issue(pending);
        for (int i = 0; i < 4; i++) assertEquals("INVALID", verify(pending, second.id(), "wrong"));
        server.restart();
        assertEquals("CONSUMED", verify(consumed, first.id(), consumedCode));
        assertEquals("PHONE_RATE_LIMITED", send(consumed).result());
        assertEquals(4, scalar("failed_events", second.id()));
        // Không phụ thuộc thời gian restart có ăn hết TTL của mã đang chờ hay không.
        try (var connection = server.connection(); var query = sql(connection, "inspect_challenge", second.id());
             var rows = query.executeQuery()) {
            assertTrue(rows.next()); assertEquals(4, rows.getInt("failed_attempts"));
        }
    }

    /** Số chỉ là dữ liệu giả; tiền tố di động hợp lệ và mỗi test có IP/session riêng. */
    private Map<String, String> user() {
        int number = sequence.incrementAndGet();
        return Map.of("phone", "+8491" + String.format(java.util.Locale.ROOT, "%07d", number),
                "ip", "10.77." + (number / 250) + "." + (number % 250 + 1),
                "session", UUID.randomUUID().toString(), "purpose", "CUSTOMER_LOGIN");
    }

    /** Tạo biến thể fixture mà không sửa đối tượng đang dùng ở luồng khác. */
    private Map<String, String> changed(Map<String, String> input, String key, String value) {
        var result = new HashMap<>(input); result.put(key, value); return result;
    }

    /** Nhánh phát mã phải thành công trước khi dùng làm tiền điều kiện của test khác. */
    private Issued issue(Map<String, String> user) throws Exception {
        var result = send(user); assertEquals("SENT", result.result()); assertFalse(result.id().isBlank()); return result;
    }

    /** Gọi SPI qua HTTP/transaction Keycloak thật và chỉ đọc receipt, không nhận OTP trong response. */
    private Issued send(Map<String, String> user) throws Exception {
        String[] parts = server.call("issue", user).split("\\|", -1); assertEquals(2, parts.length);
        return new Issued(parts[0], parts[1]);
    }

    /** Không chuyển lỗi hạ tầng sang INVALID trong helper test. */
    private String verify(Map<String, String> user, String id, String code) throws Exception {
        return server.call("verify", changed(changed(user, "id", id), "code", code));
    }

    /** Lịch sử thử chỉ được thêm trong database container, không thay tham số BR-129. */
    private void seed(String id, String kind, int ageSeconds, int count) throws Exception {
        assertEquals(count, update("seed_events", kind, ageSeconds, id, count));
    }

    /** Thao tác chuẩn bị dữ liệu có connection/autocommit riêng ngoài request. */
    private int update(String name, Object... values) throws Exception {
        try (var connection = server.connection(); var statement = sql(connection, name, values)) { return statement.executeUpdate(); }
    }

    /** Quan sát độc lập các kết quả đã commit. */
    private long scalar(String name, Object... values) throws Exception {
        try (var connection = server.connection(); var statement = sql(connection, name, values); var rows = statement.executeQuery()) {
            assertTrue(rows.next()); return rows.getLong(1);
        }
    }

    /** SQL test nằm trong resource riêng, không nối input thành câu lệnh. */
    private PreparedStatement sql(Connection connection, String name, Object... values) throws Exception {
        try (var input = getClass().getResourceAsStream("/sql/otp/" + name + ".sql")) {
            assertNotNull(input, name);
            var statement = connection.prepareStatement(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            return statement;
        }
    }

    /** Hàng rào bắt đầu thật, timeout hữu hạn; exception của một luồng luôn làm test đỏ. */
    private List<String> race(Callable<String> first, Callable<String> second) throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
            var one = pool.submit(() -> { ready.countDown(); assertTrue(start.await(10, TimeUnit.SECONDS)); return first.call(); });
            var two = pool.submit(() -> { ready.countDown(); assertTrue(start.await(10, TimeUnit.SECONDS)); return second.call(); });
            assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
            var outcomes = new ArrayList<>(List.of(one.get(50, TimeUnit.SECONDS), two.get(50, TimeUnit.SECONDS)));
            outcomes.sort(String::compareTo); return outcomes;
        }
    }

    /** Không dùng sleep cố định làm bằng chứng đồng thời: chờ hai backend thật bị khoá trong PostgreSQL. */
    private void awaitTwoBlockedTransactions() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            if (scalar("waiting_transactions") == 2) return;
            Thread.sleep(25);
        }
        fail("Expected both OTP requests to reach PostgreSQL locks within 20 seconds.");
    }

    /** Receipt thử không chứa OTP. */
    private record Issued(String result, String id) { }
}
