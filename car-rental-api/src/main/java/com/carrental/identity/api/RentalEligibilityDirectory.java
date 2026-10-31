package com.carrental.identity.api;

import java.time.Instant;

/** Cổng cross-module kiểm giấy tờ thuê tự lái theo BR-106/106b/128/130, dùng lúc tạo đơn và bàn giao. */
public interface RentalEligibilityDirectory {
    /**
     * Bên gọi đã xác định đúng tài khoản và đọc thuộc tính xe tin cậy; đây không phải cổng xác thực/phân quyền.
     * Kiểm CCCD xác minh/hạn rồi GPLX xác minh/hạn, hạng, số chỗ/hộp số, trên bản active VERIFIED.
     * Không có bản active (kể cả tài khoản chưa tồn tại) trả lỗi chưa xác minh tương ứng.
     * Hạn ngày D còn hiệu lực hết ngày D theo múi giờ ứng dụng (Việt Nam); dùng giờ trả thực tế, chưa cộng đệm.
     * Bảng hạng lấy tại thời điểm kiểm, không lấy tại tripEnd. Không cache kết quả giữa đặt xe và bàn giao.
     * Không kiểm lịch trống, cửa sổ đặt, vai hoặc consent; không mở ảnh, giải mã số hay ghi audit lượt xem.
     * Có thể gọi ngoài HTTP vì tài khoản được truyền tường minh, không sử dụng CallerDirectory.
     *
     * @param accountCode mã TK của tài khoản cần kiểm
     * @param seats số chỗ theo BR-018
     * @param transmission MANUAL hoặc AUTOMATIC, không phụ thuộc enum nội bộ của vehicle
     * @param tripEnd thời điểm trả xe thực tế; bên gọi chịu trách nhiệm kiểm khoảng thuê
     * @throws com.carrental.shared.error.DomainException nếu đầu vào sai hoặc giấy tờ chưa đủ điều kiện
     */
    void requireSelfDriveEligible(String accountCode, int seats, String transmission, Instant tripEnd);
}
