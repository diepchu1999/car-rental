package com.carrental.identity.domain;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** BR-128: ngày cấp chọn một bảng duy nhất; mốc và quyền theo hạng đều là dữ liệu. */
class LicenseClassPolicyTest {
    private static final LicenseClassTable OLD = new LicenseClassTable(Map.of("LOCAL_CLASS", Set.of(4)));
    private static final LicenseClassTable NEW = new LicenseClassTable(Map.of("LOCAL_CLASS", Set.of(16)));

    /** Không gộp hai bảng kể cả tên hạng giống nhau, đúng mốc thuộc chế độ mới. */
    @Test void selectsExactlyOneTableIncludingCutoverDate() {
        var date = LocalDate.of(2025, 1, 1);
        var policy = new LicenseClassPolicy(date, OLD, NEW);
        assertSame(OLD, policy.tableFor(date.minusDays(1)));
        assertSame(NEW, policy.tableFor(date));
        assertSame(NEW, policy.tableFor(date.plusDays(1)));
        assertFalse(policy.tableFor(date).allowsSeats("LOCAL_CLASS", 4));
        assertFalse(policy.tableFor(date.minusDays(1)).allowsSeats("LOCAL_CLASS", 16));
    }

    /** Thay mốc cấu hình làm đổi kết quả, không có ngày 2025 đóng cứng trong Java. */
    @Test void followsConfiguredCutoverDate() {
        var issued = LocalDate.of(2027, 1, 1);
        assertSame(NEW, new LicenseClassPolicy(LocalDate.of(2025, 1, 1), OLD, NEW).tableFor(issued));
        assertSame(OLD, new LicenseClassPolicy(LocalDate.of(2028, 1, 1), OLD, NEW).tableFor(issued));
    }

    /** Bộ cấu hình và ngày tra cứu phải đủ; không tự chọn bảng nếu thiếu ngày cấp. */
    @Test void rejectsMissingPolicyComponentsOrIssueDate() {
        var date = LocalDate.of(2025, 1, 1);
        assertThrows(NullPointerException.class, () -> new LicenseClassPolicy(null, OLD, NEW));
        assertThrows(NullPointerException.class, () -> new LicenseClassPolicy(date, null, NEW));
        assertThrows(NullPointerException.class, () -> new LicenseClassPolicy(date, OLD, null));
        assertThrows(NullPointerException.class, () -> new LicenseClassPolicy(date, OLD, NEW).tableFor(null));
    }
}
