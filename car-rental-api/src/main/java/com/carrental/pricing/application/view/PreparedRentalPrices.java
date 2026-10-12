package com.carrental.pricing.application.view;

/** Hàm tính thuần giữ lịch đã phân loại, không đọc cấu hình lại cho từng xe (BR-126/218). */
@FunctionalInterface
public interface PreparedRentalPrices {
    /** Nhận một giá ngày và trả tổng các dòng tiền thuê đã làm tròn, chưa gồm bảo hiểm. */
    long rentalAmount(long dailyRate);
}
