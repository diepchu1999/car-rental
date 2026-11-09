package com.carrental.identity.application.port.in;

import com.carrental.identity.application.query.CheckRentalEligibilityQuery;

/** Cổng đọc kiểm giấy tờ cho thuê tự lái theo BR-106/106b/128/130; không giữ chỗ hoặc tạo đơn. */
public interface CheckRentalEligibilityUseCase {
    /** Thành công khi không ném lỗi; không trả dữ liệu giấy tờ ra ngoài module identity. */
    void check(CheckRentalEligibilityQuery query);
}
