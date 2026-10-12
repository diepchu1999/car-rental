package com.carrental.pricing.application.port.in;

import com.carrental.pricing.application.query.PrepareRentalPricesQuery;
import com.carrental.pricing.application.view.PreparedRentalPrices;

/** Use case đọc chuẩn bị giá BR-126, không tạo báo giá hoặc kiểm giữ chỗ. */
public interface PrepareRentalPricesUseCase {
    /** Chuẩn bị theo một bộ cấu hình tại một mốc thời gian, tái dùng cho toàn bộ ứng viên. */
    PreparedRentalPrices prepare(PrepareRentalPricesQuery query);
}
