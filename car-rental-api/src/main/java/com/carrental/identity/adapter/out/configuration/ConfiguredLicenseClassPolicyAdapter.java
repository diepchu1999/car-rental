package com.carrental.identity.adapter.out.configuration;

import com.carrental.identity.application.port.out.ReadLicenseClassPolicyPort;
import com.carrental.identity.domain.LicenseClassTable;
import com.carrental.identity.domain.LicenseClassPolicy;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * BR-128, ADR-0013: nguồn local tạm, chưa có admin chỉnh hoặc lịch sử hiệu lực.
 * Mốc at bắt buộc nhưng chưa dùng chọn phiên bản. Đổi cấu hình cần restart; thay YAML đóng trong JAR
 * cần build lại. Phải thay bằng config và xác nhận bảng với người hiểu luật trước khi chạy khách thật.
 */
@Component
class ConfiguredLicenseClassPolicyAdapter implements ReadLicenseClassPolicyPort {
    private static final String PROPERTY = "car-rental.identity.license-classes";
    private final LicenseClassPolicy policy;

    /** Bind đủ mốc và hai bảng ngay lúc tạo bean; thiếu/sai dữ liệu phải ngăn khởi động. */
    ConfiguredLicenseClassPolicyAdapter(Environment environment) {
        try {
            var binder = Binder.get(environment);
            String date = binder.bind(PROPERTY + ".cutover-date", String.class)
                    .orElseThrow(() -> new IllegalArgumentException("License class cutover date is required."));
            policy = new LicenseClassPolicy(LocalDate.parse(date),
                    readTable(binder, "before-cutover"), readTable(binder, "from-cutover"));
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Invalid configuration: " + PROPERTY, failure);
        }
    }

    /** Kiểm từng bảng độc lập; cùng tên hạng được phép có phạm vi khác nhau giữa hai bảng. */
    private static LicenseClassTable readTable(Binder binder, String regime) {
        String property = PROPERTY + "." + regime;
        var entries = binder.bind(property, Bindable.listOf(ClassEntry.class))
                .orElseThrow(() -> new IllegalArgumentException("License class table is required: " + property));
        var rows = new HashMap<String, Set<Integer>>();
        for (var entry : entries) {
            if (entry == null || entry.seats() == null || entry.seats().stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("License class seats are required: " + property);
            }
            var seats = Set.copyOf(entry.seats());
            if (seats.size() != entry.seats().size() || rows.putIfAbsent(entry.code(), seats) != null) {
                throw new IllegalArgumentException("Duplicate license class or seat entry: " + property);
            }
        }
        return new LicenseClassTable(rows);
    }

    /** Nguồn local trả một bộ bất biến cho mọi mốc đánh giá; ngày cấp dùng ở domain để chọn bảng. */
    @Override public LicenseClassPolicy policyAt(Instant at) {
        Objects.requireNonNull(at, "at");
        return policy;
    }

    /** Dạng bind nội bộ giữ nguyên danh sách để phát hiện khai báo trùng trước khi đổi sang tập. */
    record ClassEntry(String code, List<Integer> seats) { }
}
