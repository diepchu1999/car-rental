package com.carrental.search.application.service;

import com.carrental.search.SearchTestQueries.Input;
import com.carrental.search.domain.*;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.shared.rental.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;
import static com.carrental.search.SearchTestQueries.input;
import static org.junit.jupiter.api.Assertions.*;

/** Kiểm cursor theo api-guideline §8: không làm tròn, không dùng lẫn bộ lọc và lỗi đầu vào rõ ràng. */
class SearchCursorCodecTest {
    /** Round-trip giữ nguyên từng bit khoảng cách và mã xe, không đưa ID nội bộ vào khóa cursor. */
    @Test
    void roundTripsExactDistanceAndVehicleCode() {
        var hash = SearchCursorCodec.fingerprint(input().build(), 10);
        var position = new SearchPosition(Math.nextUp(123.456789), "XE-Z9AB01");
        String token = SearchCursorCodec.encode(position, hash);
        assertEquals(67, token.length());
        assertTrue(token.matches("[A-Za-z0-9_-]+"));
        var decoded = SearchCursorCodec.decode(token, hash).orElseThrow();
        assertEquals(position, decoded);
        assertEquals(Double.doubleToLongBits(position.distanceMeters()),
                Double.doubleToLongBits(decoded.distanceMeters()));
    }

    /** Giải mã độc lập toàn bộ payload: chỉ có version/hash/khoảng cách/mã, không còn chỗ cho ID số. */
    @Test
    void decodedPayloadContainsVehicleCodeAndNoNumericVehicleId() {
        var hash = SearchCursorCodec.fingerprint(input().build(), 10);
        var position = new SearchPosition(123.5, "XE-ABC123");
        byte[] bytes = Base64.getUrlDecoder().decode(SearchCursorCodec.encode(position, hash));
        assertEquals(50, bytes.length);
        var payload = ByteBuffer.wrap(bytes);
        assertEquals(2, payload.get());
        byte[] storedHash = new byte[32];
        payload.get(storedHash);
        assertArrayEquals(hash, storedHash);
        assertEquals(position.distanceMeters(), payload.getDouble());
        byte[] code = new byte[9];
        payload.get(code);
        assertEquals(position.vehicleCode(), new String(code, StandardCharsets.US_ASCII));
        assertFalse(payload.hasRemaining(), "Cursor must not contain an additional numeric vehicle ID.");
    }

    /** Cursor v1 đúng cấu trúc cũ vẫn bị từ chối, không được tiếp tục với thứ tự khác. */
    @Test
    void rejectsLegacyVersionOneWithNumericId() {
        var hash = SearchCursorCodec.fingerprint(input().build(), 10);
        byte[] legacy = ByteBuffer.allocate(49).put((byte) 1).put(hash)
                .putDouble(0).putLong(123456789L).array();
        assertInvalid(() -> SearchCursorCodec.decode(encode(legacy), hash));
    }

    /** Null biểu diễn trang đầu, không cần phát một token giả. */
    @Test
    void acceptsAbsentCursor() {
        assertTrue(SearchCursorCodec.decode(null,
                SearchCursorCodec.fingerprint(input().build(), 10)).isEmpty());
    }

    /** Cursor rỗng, sai alphabet, quá dài hoặc thêm padding đều không phải định dạng canonical. */
    @ParameterizedTest
    @ValueSource(strings = {"", " ", "not-a-cursor", "%%%%", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void rejectsMalformedEncoding(String token) {
        assertInvalid(() -> SearchCursorCodec.decode(token, SearchCursorCodec.fingerprint(input().build(), 10)));
    }

    /** Phiên bản chưa hỗ trợ và padding thêm vào phải bị từ chối, không đoán cấu trúc token. */
    @Test
    void rejectsUnknownVersionAndPadding() {
        var hash = SearchCursorCodec.fingerprint(input().build(), 10);
        String token = SearchCursorCodec.encode(new SearchPosition(0, "XE-000001"), hash);
        byte[] bytes = Base64.getUrlDecoder().decode(token);
        bytes[0] = 3;
        assertInvalid(() -> SearchCursorCodec.decode(encode(bytes), hash));
        bytes[0] = 1;
        assertInvalid(() -> SearchCursorCodec.decode(encode(bytes), hash));
        assertInvalid(() -> SearchCursorCodec.decode(token + "=", hash));
    }

    /** Không nhận NaN/vô cực/khoảng cách âm hoặc mã sai prefix/alphabet từ payload đã giải mã. */
    @Test
    void rejectsInvalidPositions() {
        var hash = SearchCursorCodec.fingerprint(input().build(), 10);
        for (double distance : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -1}) {
            byte[] bytes = Base64.getUrlDecoder().decode(SearchCursorCodec.encode(new SearchPosition(0, "XE-000001"), hash));
            ByteBuffer.wrap(bytes).putDouble(33, distance);
            assertInvalid(() -> SearchCursorCodec.decode(encode(bytes), hash));
        }
        for (String code : new String[]{"CN-000001", "XE-abc123", "XE-ABC12!", "XE-ABC12 "}) {
            byte[] bytes = Base64.getUrlDecoder().decode(SearchCursorCodec.encode(new SearchPosition(0, "XE-000001"), hash));
            ByteBuffer.wrap(bytes).position(41).put(code.getBytes(StandardCharsets.US_ASCII));
            assertInvalid(() -> SearchCursorCodec.decode(encode(bytes), hash));
        }
    }

    /** Mọi đầu vào ảnh hưởng tập/thứ tự kết quả đều khóa cursor vào đúng truy vấn. */
    @Test
    void bindsEverySearchCriterion() {
        var originalHash = SearchCursorCodec.fingerprint(input().build(), 10);
        String token = SearchCursorCodec.encode(new SearchPosition(0, "XE-000001"), originalHash);
        List<Consumer<Input>> changes = List.of(
                v -> v.latitude = 11.0,
                v -> v.longitude = 107.0,
                v -> v.start = v.start.plusSeconds(1),
                v -> v.end = v.end.plusSeconds(1),
                v -> v.rentalType = RentalType.HOURLY,
                v -> v.driveMode = DriveMode.WITH_DRIVER,
                v -> v.pickupMethod = PickupMethod.DELIVERY,
                v -> v.sort = SearchSort.PRICE_ASC,
                v -> v.filters = new VehicleSearchFilters(5, null, null, null, null, null),
                v -> v.filters = new VehicleSearchFilters(null, "MANUAL", null, null, null, null),
                v -> v.filters = new VehicleSearchFilters(null, null, "DIESEL", null, null, null),
                v -> v.filters = new VehicleSearchFilters(null, null, null, "Toyota", null, null),
                v -> v.filters = new VehicleSearchFilters(null, null, null, null, "Vios", null),
                v -> v.filters = new VehicleSearchFilters(null, null, null, null, null, true),
                v -> v.filters = new VehicleSearchFilters(null, null, null, null, null, false)
        );
        for (Consumer<Input> change : changes) {
            var values = input();
            change.accept(values);
            assertInvalid(() -> SearchCursorCodec.decode(token, SearchCursorCodec.fingerprint(values.build(), 10)));
        }
        assertInvalid(() -> SearchCursorCodec.decode(token, SearchCursorCodec.fingerprint(input().build(), 11)));
    }

    /** Đổi số lượng mỗi trang hoặc gửi tường minh mặc định không đổi tập tìm kiếm. */
    @Test
    void permitsDifferentPageSizeAndExplicitDefaults() {
        var hash = SearchCursorCodec.fingerprint(input().build(), 10);
        var values = input();
        values.limit = 100;
        values.radiusKm = 10.0;
        values.pickupMethod = PickupMethod.BRANCH;
        values.sort = SearchSort.NEAREST;
        assertArrayEquals(hash, SearchCursorCodec.fingerprint(values.build(), 10));
    }

    /** Prefix độ dài ngăn chuỗi có dấu phân cách tạo cùng nội dung ghép; không áp giới hạn hãng/dòng tùy tiện. */
    @Test
    void handlesLongTextAndUnambiguousFieldBoundaries() {
        var first = input();
        first.filters = new VehicleSearchFilters(null, null, null, "a|b", "c", null);
        var second = input();
        second.filters = new VehicleSearchFilters(null, null, null, "a", "b|c", null);
        assertFalse(java.util.Arrays.equals(SearchCursorCodec.fingerprint(first.build(), 10),
                SearchCursorCodec.fingerprint(second.build(), 10)));
        first.filters = new VehicleSearchFilters(null, null, null, "a".repeat(70_000), "Model", null);
        assertEquals(32, SearchCursorCodec.fingerprint(first.build(), 10).length);
    }

    /** Mã hóa payload lỗi chủ động để lỗi phải nằm ở validator, không phải tạo fixture thất bại. */
    private String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Cursor hỏng phải là INVALID_REQUEST, không thành lỗi hệ thống. */
    private void assertInvalid(org.junit.jupiter.api.function.Executable action) {
        var error = assertThrowsExactly(DomainException.class, action);
        assertEquals(ErrorCode.INVALID_REQUEST, error.errorCode());
        assertEquals(DomainException.Category.INVALID_INPUT, error.category());
    }
}
