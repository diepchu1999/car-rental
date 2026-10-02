package com.carrental.availability.api;

/**
 * Trả thông tin định danh tối thiểu của khóa lịch vừa được ghi.
 *
 * <p>Module gọi sử dụng code cho các thao tác xác nhận, giải phóng,
 * bàn giao và hoàn tất. Không dùng bookingCode thay cho mã reservation
 * vì một đơn có thể có nhiều khóa lịch khi gia hạn theo BR-427.
 *
 * <p>id là khóa chính nội bộ, không đưa lên URL.
 * Record không công bố aggregate hoặc trạng thái nội bộ của domain.
 *
 * <p>Kết quả thuộc transaction đang thực hiện. Nếu transaction
 * bên gọi rollback, bản ghi vừa tạo cũng phải rollback theo.
 *
 * @param id khóa chính nội bộ do database sinh
 * @param code mã nghiệp vụ của reservation
 */
public record ReservationRef(
        long id,
        String code
) {
}