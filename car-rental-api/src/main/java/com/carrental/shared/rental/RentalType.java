package com.carrental.shared.rental;

/**
 * Từ vựng gói thuê dùng chung theo BR-113 và ADR-0004.
 * Không chứa quy tắc; chỉ *PolicyResolver được rẽ nhánh trên enum này.
 */
public enum RentalType {
    /** Thuê theo giờ; điều kiện tối thiểu do policy của booking kiểm. */
    HOURLY,
    /** Thuê theo ngày. */
    DAILY,
    /** Thuê dài hạn theo tháng; chưa mở tìm kiếm trong lát cắt 1. */
    MONTHLY
}
