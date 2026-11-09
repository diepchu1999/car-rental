package com.carrental.identity.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** BR-128: chứng minh bảng là dữ liệu, từ chối đầu vào sai và không thể bị sửa sau khi tạo. */
class LicenseClassTableTest {
    /** Hạng hoàn toàn mới vẫn tra được, không cần sửa enum hoặc nhánh if trong Java. */
    @Test void readsAnArbitraryConfiguredClass() {
        var table = new LicenseClassTable(Map.of("LOCAL_TEST_CLASS", Set.of(4, 16)));
        assertTrue(table.supportsClass("LOCAL_TEST_CLASS"));
        assertTrue(table.allowsSeats("LOCAL_TEST_CLASS", 16));
        assertFalse(table.allowsSeats("LOCAL_TEST_CLASS", 7));
        assertFalse(table.supportsClass("B")); assertFalse(table.allowsSeats("B", 4));
    }

    /** Không tự chuẩn hoá hạng ngoài hợp đồng; lớp application sẽ phân biệt hạng không hỗ trợ. */
    @Test void doesNotInferOrNormalizeClassNames() {
        var table = new LicenseClassTable(Map.of("B", Set.of(4)));
        assertFalse(table.supportsClass("b")); assertFalse(table.supportsClass(" B "));
        assertFalse(table.allowsSeats("B1", 4)); assertFalse(table.allowsSeats("B", 5));
    }

    /** Sao chép cả map và tập con; cả đầu vào lẫn accessor đều không làm đổi bảng đã tạo. */
    @Test void deepCopiesBothLevels() {
        var seats = new HashSet<>(Set.of(4)); var input = new HashMap<String, Set<Integer>>(); input.put("B", seats);
        var table = new LicenseClassTable(input); seats.add(16); input.clear();
        assertTrue(table.allowsSeats("B", 4)); assertFalse(table.allowsSeats("B", 16));
        assertThrows(UnsupportedOperationException.class, () -> table.allowedSeatsByClass().clear());
        assertThrows(UnsupportedOperationException.class, () -> table.allowedSeatsByClass().get("B").add(16));
    }

    /** Thiếu bảng hoặc bảng rỗng không được coi là một chính sách hợp lệ. */
    @Test void rejectsMissingOrEmptyTable() {
        assertThrows(NullPointerException.class, () -> new LicenseClassTable(null));
        assertThrows(IllegalArgumentException.class, () -> new LicenseClassTable(Map.of()));
    }

    /** Hạng trống/null hoặc có khoảng trắng ngoài làm lỗi ngay lúc tạo dữ liệu chính sách. */
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" ", " B", "B "})
    void rejectsInvalidClassCode(String code) {
        var input = new HashMap<String, Set<Integer>>(); input.put(code, Set.of(4));
        assertThrows(IllegalArgumentException.class, () -> new LicenseClassTable(input));
    }

    /** Tập số chỗ null, rỗng, chứa null hoặc không dương đều không hợp lệ. */
    @Test void rejectsInvalidSeatSets() {
        var input = new HashMap<String, Set<Integer>>();
        input.put("B", null); assertThrows(IllegalArgumentException.class, () -> new LicenseClassTable(input));
        input.put("B", Set.of()); assertThrows(IllegalArgumentException.class, () -> new LicenseClassTable(input));
        var seats = new HashSet<Integer>(); seats.add(null); input.put("B", seats);
        assertThrows(IllegalArgumentException.class, () -> new LicenseClassTable(input));
        input.put("B", Set.of(0)); assertThrows(IllegalArgumentException.class, () -> new LicenseClassTable(input));
        input.put("B", Set.of(-1)); assertThrows(IllegalArgumentException.class, () -> new LicenseClassTable(input));
    }

    /** Tra cứu sai hợp đồng không được nhận một câu trả lời đủ điều kiện. */
    @Test void rejectsInvalidLookupArguments() {
        var table = new LicenseClassTable(Map.of("B", Set.of(4)));
        assertThrows(NullPointerException.class, () -> table.supportsClass(null));
        assertThrows(NullPointerException.class, () -> table.allowsSeats(null, 4));
        assertThrows(IllegalArgumentException.class, () -> table.allowsSeats("B", 0));
        assertThrows(IllegalArgumentException.class, () -> table.allowsSeats("B", -1));
    }
}
