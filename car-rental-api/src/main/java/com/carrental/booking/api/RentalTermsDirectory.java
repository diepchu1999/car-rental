package com.carrental.booking.api;

import com.carrental.shared.rental.PickupMethod;
import com.carrental.shared.rental.RentalType;
import java.time.Instant;

/**
 * Cổng đọc điều kiện thuê do booking sở hữu, dùng chung cho tìm kiếm và đặt xe (BR-125).
 * Không truy cập CSDL, không khóa lịch và không kiểm xe trống.
 */
public interface RentalTermsDirectory {
    /**
     * Phân giải một lần rồi kiểm khoảng thuê theo BR-113, BR-119 và BR-121.
     * Đồng hồ và múi giờ do ứng dụng cung cấp, không nhận thời điểm hiện tại từ client.
     * Cổng này không quyết định tính năng đã được mở theo lát cắt: search tự từ chối
     * gói tháng, giao tận nơi hoặc có tài xế trước khi gọi ở lát cắt 1.
     *
     * @param rentalType gói thuê bắt buộc
     * @param pickupMethod cách nhận xe bắt buộc; bên gọi áp mặc định của API
     * @param startInclusive thời điểm nhận xe thực tế, chưa cộng đệm
     * @param endExclusive thời điểm trả xe thực tế, phải sau thời điểm nhận
     * @return điều kiện hợp lệ với đệm chưa cộng; bên gọi áp đệm đúng một lần
     * @throws com.carrental.shared.error.DomainException nếu thiếu đầu vào, khoảng sai hoặc vi phạm điều kiện
     */
    RentalTerms getTerms(RentalType rentalType, PickupMethod pickupMethod,
                         Instant startInclusive, Instant endExclusive);
}
