package com.carrental.search.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** Khóa thứ tự khoảng cách rồi mã xe theo BR-112/126 và database-guideline §2. */
class SearchPositionTest {
    /** Khoảng cách luôn ưu tiên; chỉ khi bằng nhau mới so mã ASCII, không dùng locale. */
    @Test
    void ordersByExactDistanceThenVehicleCode() {
        var first = new SearchPosition(10, "XE-000001");
        var second = new SearchPosition(10, "XE-AAAAAA");
        var farther = new SearchPosition(Math.nextUp(10.0), "XE-000000");
        assertTrue(first.compareTo(second) < 0);
        assertTrue(second.compareTo(first) > 0);
        assertTrue(second.compareTo(farther) < 0);
        assertEquals(0, first.compareTo(new SearchPosition(10, "XE-000001")));
    }

    /** BR-126: giá ưu tiên hơn khoảng cách; hai khóa bằng nhau mới so mã, không dùng ID. */
    @Test
    void ordersPriceBeforeDistanceAndCode() {
        var cheap = new SearchPosition(SearchSort.PRICE_ASC, 1_000_000, 500, "XE-ZZZZZZ");
        var near = new SearchPosition(SearchSort.PRICE_ASC, 2_000_000, 10, "XE-ZZZZZZ");
        var far = new SearchPosition(SearchSort.PRICE_ASC, 2_000_000, 20, "XE-AAAAAA");
        var tie = new SearchPosition(SearchSort.PRICE_ASC, 2_000_000, 10, "XE-AAAAAA");
        assertTrue(cheap.compareTo(near) < 0);
        assertTrue(near.compareTo(far) < 0);
        assertTrue(tie.compareTo(near) < 0);
        assertThrowsExactly(IllegalArgumentException.class, () -> cheap.compareTo(new SearchPosition(500, "XE-ZZZZZZ")));
    }

    /** Âm không và dương không là cùng một vị trí, tránh hai cách mã hóa cùng khoảng cách. */
    @Test
    void normalizesNegativeZero() {
        assertEquals(new SearchPosition(0.0, "XE-ABC123"), new SearchPosition(-0.0, "XE-ABC123"));
    }

    /** Mã sai không thể đi vào encoder rồi bị mất ký tự hoặc thay bằng dấu hỏi ASCII. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "CN-ABC123", "XE-abc123", "XE-ABC12", "XE-ABC1234", "XE-ÁBC123"})
    void rejectsInvalidVehicleCodes(String code) {
        assertThrowsExactly(IllegalArgumentException.class, () -> new SearchPosition(0, code));
    }

    /** Khóa khoảng cách phải hữu hạn và không âm, kể cả khi được dựng trực tiếp trong Java. */
    @ParameterizedTest
    @ValueSource(doubles = {-1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void rejectsInvalidDistances(double distance) {
        assertThrowsExactly(IllegalArgumentException.class, () -> new SearchPosition(distance, "XE-ABC123"));
    }
}
