package com.carrental.booking.application.service;

import com.carrental.booking.application.query.BookingPagePosition;
import com.carrental.shared.error.DomainException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;

/**
 * Cursor v1: dấu chủ, mốc tạo chính xác và mã đơn; không chứa ID số hoặc token.
 * Dấu chủ ngăn dùng nhầm cursor, không phải chữ ký cấp quyền: SQL luôn lọc chủ từ CallerDirectory.
 * Không đóng băng toàn bộ tập kết quả; đơn mới phía trước cursor xuất hiện khi tải lại trang đầu.
 */
final class BookingCursorCodec {
    private static final byte VERSION = 1;
    private static final int HASH_BYTES = 32;
    private static final int CODE_BYTES = 14;
    private static final int BYTES = 1 + HASH_BYTES + Long.BYTES + Integer.BYTES + CODE_BYTES;

    /** Tiện ích thuần Java không giữ trạng thái request. */
    private BookingCursorCodec() { }

    /** Không ràng buộc limit: khách có thể đổi kích thước trang mà vẫn tiếp tục đúng khóa. */
    static String encode(BookingPagePosition position, String owner) {
        var bytes = ByteBuffer.allocate(BYTES).put(VERSION).put(ownerHash(owner))
                .putLong(position.createdAt().getEpochSecond()).putInt(position.createdAt().getNano())
                .put(position.code().getBytes(StandardCharsets.US_ASCII)).array();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Chỉ null là trang đầu; cursor hỏng/sai phiên bản/sai chủ đều thành lỗi đầu vào trước SQL. */
    static BookingPagePosition decode(String token, String owner) {
        if (token == null) return null;
        try {
            if (!token.matches("[A-Za-z0-9_-]{79}")) throw new IllegalArgumentException();
            byte[] bytes = Base64.getUrlDecoder().decode(token);
            if (bytes.length != BYTES || !Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(token)) {
                throw new IllegalArgumentException();
            }
            var data = ByteBuffer.wrap(bytes);
            if (data.get() != VERSION) throw new IllegalArgumentException();
            var hash = new byte[HASH_BYTES];
            data.get(hash);
            if (!MessageDigest.isEqual(hash, ownerHash(owner))) throw new IllegalArgumentException();
            long seconds = data.getLong();
            int nanos = data.getInt();
            if (nanos < 0 || nanos >= 1_000_000_000) throw new IllegalArgumentException();
            var code = new byte[CODE_BYTES];
            data.get(code);
            return new BookingPagePosition(Instant.ofEpochSecond(seconds, nanos), new String(code, StandardCharsets.US_ASCII));
        } catch (IllegalArgumentException | DateTimeException failure) {
            throw DomainException.invalidInput("cursor is invalid or belongs to another account.");
        }
    }

    /** Dùng dấu băm thay vì đưa mã tài khoản vào cursor; không dùng bí mật hoặc quyền từ cursor. */
    private static byte[] ownerHash(String owner) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(owner.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is required for booking cursors.", failure);
        }
    }
}
