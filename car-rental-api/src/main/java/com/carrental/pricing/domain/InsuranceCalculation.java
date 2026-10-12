package com.carrental.pricing.domain;

/** Hai cách tính bảo hiểm cấu hình được theo BR-231. */
public enum InsuranceCalculation {
    /** Số nguyên VND nhân số ngày tính tiền; gói giờ luôn một ngày. */
    PER_BILLING_DAY,
    /** Tỷ lệ thập phân nhân tổng tiền thuê đã làm tròn. */
    PERCENT_OF_RENT
}
