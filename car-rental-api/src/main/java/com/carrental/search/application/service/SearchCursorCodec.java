package com.carrental.search.application.service;

import com.carrental.search.application.query.SearchVehiclesQuery;
import com.carrental.search.domain.SearchPosition;
import com.carrental.search.domain.SearchSort;
import com.carrental.shared.error.DomainException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Optional;

/**
 * Cursor opaque v3 có dấu SHA-256, khóa giá/khoảng cách/mã; NEAREST dùng sentinel giá 0.
 * Khoảng cách giữ nguyên 64 bit, không làm tròn nên trang sau không đổi khóa thứ tự.
 * Dấu điều kiện chống dùng nhầm cursor, không phải chữ ký xác thực hay snapshot CSDL.
 * Không cần secret vì cursor của danh sách công khai không cấp quyền truy cập dữ liệu.
 */
final class SearchCursorCodec {
    private static final byte VERSION = 3;
    private static final int HASH_BYTES = 32;
    private static final int VEHICLE_CODE_BYTES = 9;
    private static final int TOKEN_BYTES = 1 + HASH_BYTES + Long.BYTES + Double.BYTES + VEHICLE_CODE_BYTES;

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
                    filters.collateralFree() == null ? null : filters.collateralFree().toString(),
                    filters.minRentalAmount() == null ? null : filters.minRentalAmount().toString(),
                    filters.maxRentalAmount() == null ? null : filters.maxRentalAmount().toString()
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

    /** Mã hóa 58 byte: phiên bản, hash, giá (NEAREST=0), khoảng cách, mã; không có ID số. */
    static String encode(SearchPosition position, byte[] fingerprint) {
        if (fingerprint.length != HASH_BYTES) {
            throw new IllegalArgumentException("Invalid search fingerprint length.");
        }
        byte[] bytes = ByteBuffer.allocate(TOKEN_BYTES).put(VERSION).put(fingerprint)
                .putLong(position.rentalAmount())
                .putDouble(position.distanceMeters())
                .put(position.vehicleCode().getBytes(StandardCharsets.US_ASCII)).array();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Chặn cursor hỏng/sai phiên bản/sai điều kiện trước khi đọc danh mục; null là trang đầu. */
    static Optional<SearchPosition> decode(String token, byte[] expectedFingerprint) {
        return decode(token, expectedFingerprint, SearchSort.NEAREST);
    }

    /** Thứ tự đã được fingerprint ràng buộc; không tự suy sort từ con số trong token. */
    static Optional<SearchPosition> decode(String token, byte[] expectedFingerprint, SearchSort sort) {
        if (token == null) {
            return Optional.empty();
        }
        try {
            if (!token.matches("[A-Za-z0-9_-]{78}")) {
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
            long rentalAmount = payload.getLong();
            double distanceMeters = payload.getDouble();
            byte[] vehicleCode = new byte[VEHICLE_CODE_BYTES];
            payload.get(vehicleCode);
            return Optional.of(new SearchPosition(sort, rentalAmount, distanceMeters,
                    new String(vehicleCode, StandardCharsets.US_ASCII)));
        } catch (IllegalArgumentException failure) {
            throw DomainException.invalidInput("cursor is invalid or does not match the search criteria.");
        }
    }
}
