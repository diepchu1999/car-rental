package com.carrental.identity.application.port.out;

import com.carrental.identity.domain.LicenseClassTable;
import java.time.Instant;

/** BR-128, ADR-0013: nguồn bảng hạng có thể thay bằng module config mà không sửa cổng kiểm điều kiện thuê. */
public interface ReadLicenseClassPolicyPort {
    /** Nhận mốc đánh giá tường minh; không tự lấy đồng hồ hoặc mặc định sang cấu hình hiện tại. */
    LicenseClassTable tableAt(Instant at);
}
