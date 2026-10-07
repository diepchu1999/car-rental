package com.carrental.search.application.service;

import com.carrental.search.application.query.SearchVehiclesQuery;
import com.carrental.search.domain.SearchPosition;
import com.carrental.shared.error.DomainException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Optional;

/**
 * Cursor opaque có phiên bản, dấu SHA-256 của điều kiện và khóa (khoảng cách, ID).
 * Khoảng cách giữ nguyên 64 bit, không làm tròn nên trang sau không đổi khóa thứ tự.
 * Dấu điều kiện chống dùng nhầm cursor, không phải chữ ký xác thực hay snapshot CSDL.
 * Không cần secret vì cursor của danh sách công khai không cấp quyền truy cập dữ liệu.
 */
final class SearchCursorCodec {
    private static final byte VERSION = 1;
    private static final int HASH_BYTES = 32;
    private static final int TOKEN_BYTES = 1 + HASH_BYTES + Double.BYTES + Long.BYTES;

    /** Tiện ích thuần Java, không có trạng thái giữa request. */
    private SearchCursorCodec() {
    }

    /**
     * Ràng buộc cursor với tất cả điều kiện ảnh hưởng tập/thứ tự kết quả.
     * Không gồm limit: khách có thể đổi kích thước trang mà vẫn tiếp tục sau cùng một khóa.
     * Bán kính dùng giá trị hiệu lực nên bỏ trống và gửi đúng mặc định là tương đương.
     */
    static byte[] fingerprint(SearchVehiclesQuery query, double effectiveRadiusKm) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            var filters = query.filters();
            for (String value : new String[]{
                    Double.toHexString(query.latitude()), Double.toHexString(query.longitude()),
                    query.startInclusive().toString(), query.endExclusive().toString(),
                    query.rentalType().name(), query.driveMode().name(), query.pickupMethod().name(),
                    Double.toHexString(effectiveRadiusKm), query.sort().name(),
                    filters.seats() == null ? null : filters.seats().toString(),
                    filters.transmission(), filters.fuelType(), filters.make(), filters.model(),
                    filters.collateralFree() == null ? null : filters.collateralFree().toString()
            }) {
                update(digest, value);
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is required for search cursors.", failure);
        }
    }

    /** Ghi tiền tố độ dài byte để null, chuỗi rỗng và chuỗi chứa dấu phân cách không nhập nhằng. */
    private static void update(MessageDigest digest, String value) {
        if (value == null) {
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(-1).array());
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    /** Mã hóa cố định 49 byte, Base64 URL-safe không padding; không đưa toàn bộ bộ lọc vào token. */
    static String encode(SearchPosition position, byte[] fingerprint) {
        if (fingerprint.length != HASH_BYTES) {
            throw new IllegalArgumentException("Invalid search fingerprint length.");
        }
        byte[] bytes = ByteBuffer.allocate(TOKEN_BYTES).put(VERSION).put(fingerprint)
                .putDouble(position.distanceMeters()).putLong(position.vehicleId()).array();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Chặn cursor hỏng/sai phiên bản/sai điều kiện trước khi đọc danh mục; null là trang đầu. */
    static Optional<SearchPosition> decode(String token, byte[] expectedFingerprint) {
        if (token == null) {
            return Optional.empty();
        }
        try {
            if (!token.matches("[A-Za-z0-9_-]{66}")) {
                throw new IllegalArgumentException("Invalid cursor encoding.");
            }
            byte[] bytes = Base64.getUrlDecoder().decode(token);
            if (bytes.length != TOKEN_BYTES
                    || !Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(token)) {
                throw new IllegalArgumentException("Invalid cursor payload.");
            }
            ByteBuffer payload = ByteBuffer.wrap(bytes);
            if (payload.get() != VERSION) {
                throw new IllegalArgumentException("Unsupported cursor version.");
            }
            byte[] actualFingerprint = new byte[HASH_BYTES];
            payload.get(actualFingerprint);
            if (!MessageDigest.isEqual(actualFingerprint, expectedFingerprint)) {
                throw new IllegalArgumentException("Cursor belongs to different search criteria.");
            }
            return Optional.of(new SearchPosition(payload.getDouble(), payload.getLong()));
        } catch (IllegalArgumentException failure) {
            throw DomainException.invalidInput("cursor is invalid or does not match the search criteria.");
        }
    }
}
