package com.carrental.identity.adapter.out.configuration;

import com.carrental.identity.application.port.out.ReadLicenseClassPolicyPort;
import com.carrental.identity.domain.LicenseClassTable;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Instant;
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
    private final LicenseClassTable table;

    /** Bind bảng ngay khi tạo bean; thiếu/sai/trùng hàng phải làm khởi động thất bại, không điền mặc định. */
    ConfiguredLicenseClassPolicyAdapter(Environment environment) {
        try {
            var entries = Binder.get(environment).bind(PROPERTY, Bindable.listOf(ClassEntry.class))
                    .orElseThrow(() -> new IllegalArgumentException("License class table is required."));
            var rows = new HashMap<String, Set<Integer>>();
            for (var entry : entries) {
                if (entry == null || entry.seats() == null || entry.seats().stream().anyMatch(Objects::isNull)) {
                    throw new IllegalArgumentException("License class seats are required.");
                }
                var seats = Set.copyOf(entry.seats());
                if (seats.size() != entry.seats().size() || rows.putIfAbsent(entry.code(), seats) != null) {
                    throw new IllegalArgumentException("Duplicate license class or seat entry.");
                }
            }
            table = new LicenseClassTable(rows);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Invalid configuration: " + PROPERTY, failure);
        }
    }

    /** Trả bảng bất biến đã kiểm tra; nguồn local hiện trả cùng bảng cho mọi mốc. */
    @Override public LicenseClassTable tableAt(Instant at) {
        Objects.requireNonNull(at, "at");
        return table;
    }

    /** Dạng bind nội bộ giữ nguyên danh sách để phát hiện khai báo trùng trước khi đổi sang tập. */
    record ClassEntry(String code, List<Integer> seats) { }
}
