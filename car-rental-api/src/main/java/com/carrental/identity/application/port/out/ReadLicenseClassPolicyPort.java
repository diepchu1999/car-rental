package com.carrental.identity.application.port.out;

import com.carrental.identity.domain.LicenseClassPolicy;
import java.time.Instant;

/** BR-128, ADR-0013: nguồn bảng hạng có thể thay bằng module config mà không sửa cổng kiểm điều kiện thuê. */
public interface ReadLicenseClassPolicyPort {
    /** Nhận mốc đánh giá theo ADR-0013; bộ trả về gồm mốc ngày cấp và cả hai bảng hạng cùng phiên bản. */
    LicenseClassPolicy policyAt(Instant at);
}
