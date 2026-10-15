package com.carrental.keycloak.otp;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;

/** BR-129: tuần tự hoá theo số/IP trên PostgreSQL, cửa sổ trượt và tiêu thụ một lần cùng transaction. */
final class OtpStore {
    private static final Duration HOUR = Duration.ofMinutes(60);
    private static final Duration DAY = Duration.ofHours(24);
    private final Connection connection;
    private final OtpPolicy policy;
    private final OtpSecrets secrets;

    /** Connection phải thuộc transaction Keycloak đang hoạt động. */
    OtpStore(Connection connection, OtpPolicy policy, OtpSecrets secrets) {
        this.connection = connection; this.policy = policy; this.secrets = secrets;
    }

    /** Chỉ chèn khi đủ mọi hạn mức; gửi lại huỷ challenge cũ của số này, kể cả ở tab khác. */
    OtpIssue issue(OtpScope scope, String remoteAddress, String id, String code) throws SQLException {
        Keys keys = keys(scope, remoteAddress);
        lock(keys);
        Instant now = now();
        Counts counts = counts(keys, now);
        if (counts.phoneHour >= policy.sendsPerPhoneHour() || counts.phoneDay >= policy.sendsPerPhoneDay())
            return OtpIssue.rejected(OtpResult.PHONE_RATE_LIMITED);
        if (counts.ipSends >= policy.sendsPerIpHour() || counts.ipFailures >= policy.failuresPerIpHour())
            return OtpIssue.rejected(OtpResult.IP_RATE_LIMITED);
        if (counts.lastSent != null && now.isBefore(counts.lastSent.plus(policy.resendDelay())))
            return OtpIssue.rejected(OtpResult.RESEND_TOO_SOON);
        try (var statement = statement("supersede", keys.phone)) { statement.executeUpdate(); }
        Instant expires = now.plus(policy.lifetime());
        try (var statement = statement("insert_challenge", id, keys.scope, keys.phone,
                secrets.digest("code", id, code), now, expires)) {
            requireOne(statement.executeUpdate());
        }
        event(keys, "SENT", now);
        return new OtpIssue(OtpResult.SENT, id, expires);
    }

    /** Khoá cùng thứ tự với phát mã; kiểm IP trước khi so mã để chặn cả mã đúng khi IP hết lượt. */
    OtpResult verify(OtpScope scope, String remoteAddress, String id, String code) throws SQLException {
        Keys keys = keys(scope, remoteAddress);
        lock(keys);
        Instant now = now();
        if (counts(keys, now).ipFailures >= policy.failuresPerIpHour()) return OtpResult.IP_RATE_LIMITED;
        try (var statement = statement("find_challenge", id, keys.scope, keys.phone);
             var rows = statement.executeQuery()) {
            if (!rows.next()) {
                event(keys, "FAILED", now);
                return OtpResult.INVALID;
            }
            String state = rows.getString("state");
            if ("CONSUMED".equals(state)) return OtpResult.CONSUMED;
            if ("EXHAUSTED".equals(state)) return OtpResult.ATTEMPTS_EXHAUSTED;
            if (!"ACTIVE".equals(state)) return OtpResult.INVALID;
            if (!now.isBefore(rows.getTimestamp("expires_at").toInstant())) return OtpResult.EXPIRED;
            int failures = rows.getInt("failed_attempts");
            boolean matches = code != null && code.matches("[0-9]{" + policy.digits() + "}")
                    && secrets.matches(rows.getString("code_digest"), secrets.digest("code", id, code));
            if (!matches) {
                boolean exhausted = failures + 1 >= policy.maximumFailures();
                try (var update = statement("record_failure", exhausted ? "EXHAUSTED" : "ACTIVE", id)) {
                    requireOne(update.executeUpdate());
                }
                event(keys, "FAILED", now);
                return exhausted ? OtpResult.ATTEMPTS_EXHAUSTED : OtpResult.INVALID;
            }
        }
        // Chốt chống phát lại: điều kiện ACTIVE và khoá đã giữ đến khi transaction commit.
        try (var statement = statement("consume", now, id)) { requireOne(statement.executeUpdate()); }
        return OtpResult.VERIFIED;
    }

    /** Khoá bền qua nhiều phiên/nút; sort toàn bộ khoá để không tạo chu trình chờ nhau. */
    private void lock(Keys keys) throws SQLException {
        for (String key : Stream.of(keys.phone, keys.ip).sorted().toList()) {
            try (var insert = statement("insert_lock", key)) { insert.executeUpdate(); }
            try (var select = statement("lock_subject", key); var rows = select.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException("OTP subject lock is missing.");
            }
        }
    }

    /** Đồng hồ CSDL lấy sau khi đợi khoá, tránh dùng thời điểm cũ để vượt hạn mức. */
    private Instant now() throws SQLException {
        try (var statement = statement("clock"); var rows = statement.executeQuery()) {
            if (!rows.next()) throw new IllegalStateException("Database time is missing.");
            return rows.getTimestamp(1).toInstant();
        }
    }

    /** Biên trái mở: bản ghi đúng 60 phút/24 giờ trước đã ra khỏi cửa sổ BR-129. */
    private Counts counts(Keys keys, Instant now) throws SQLException {
        try (var statement = statement("counts", keys.phone, now.minus(HOUR), keys.phone, now.minus(DAY),
                keys.ip, now.minus(HOUR), keys.ip, now.minus(HOUR), keys.phone,
                keys.phone, keys.ip); var rows = statement.executeQuery()) {
            if (!rows.next()) throw new IllegalStateException("OTP counters are missing.");
            Timestamp last = rows.getTimestamp("last_sent");
            return new Counts(rows.getLong("phone_hour"), rows.getLong("phone_day"),
                    rows.getLong("ip_sends"), rows.getLong("ip_failures"), last == null ? null : last.toInstant());
        }
    }

    /** Ghi sự kiện đã được cho phép vào cùng transaction với trạng thái mã. */
    private void event(Keys keys, String kind, Instant now) throws SQLException {
        try (var statement = statement("insert_event", keys.phone, keys.ip, kind, now)) {
            requireOne(statement.executeUpdate());
        }
    }

    /** Khóa HMAC có realm/phân miền; không tin địa chỉ IP lấy từ header tùy ý. */
    private Keys keys(OtpScope scope, String address) {
        if (address == null || address.isBlank() || address.length() > 64
                || !address.matches("[0-9a-fA-F:.]+")
                || (!address.contains(":") && !address.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))) {
            throw new IllegalArgumentException("A numeric peer IP address is required.");
        }
        // Chuẩn hoá biểu diễn IPv6, không DNS: chuỗi đã bị giới hạn ở ký tự IP phía trên.
        final String ip;
        try { ip = java.net.InetAddress.getByName(address).getHostAddress(); }
        catch (java.net.UnknownHostException failure) { throw new IllegalArgumentException("Invalid peer IP address."); }
        return new Keys(secrets.digest("phone", scope.realmId(), scope.phoneNumber()),
                secrets.digest("ip", scope.realmId(), ip), secrets.digest("scope", scope.realmId(),
                scope.authenticationSessionId(), scope.phoneNumber(), scope.purpose().name()));
    }

    /** Chỉ dùng prepared statement; không ghép input thành SQL hoặc tự quản lý connection. */
    private PreparedStatement statement(String name, Object... values) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(OtpSql.get(name));
        try {
            for (int i = 0; i < values.length; i++) {
                Object value = values[i];
                if (value instanceof Instant instant) statement.setTimestamp(i + 1, Timestamp.from(instant));
                else statement.setString(i + 1, (String) value);
            }
            return statement;
        } catch (SQLException | RuntimeException failure) { statement.close(); throw failure; }
    }

    /** Thao tác trên challenge đơn phải ghi đúng một dòng; sai là lỗi hạ tầng. */
    private static void requireOne(int affected) {
        if (affected != 1) throw new IllegalStateException("Expected exactly one OTP row to be changed.");
    }
    /** Chỉ giữ dấu HMAC trong các truy vấn; không mang số/IP bản rõ. */
    private record Keys(String phone, String ip, String scope) { }
    /** Bộ đếm cửa sổ lấy dưới cùng các khoá bảo vệ ghi. */
    private record Counts(long phoneHour, long phoneDay, long ipSends, long ipFailures, Instant lastSent) { }
}
