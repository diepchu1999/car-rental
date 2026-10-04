package com.carrental.availability.application.port.in;

import com.carrental.availability.application.command.ConfirmReservationCommand;
import com.carrental.shared.error.DomainException;

/**
 * Cổng nội bộ để xác nhận chỗ giữ còn hạn theo BR-103 và status-flow §2.
 *
 * <p>Module khác phải gọi AvailabilityDirectory, không import use case này.
 */
public interface ConfirmReservationUseCase {

    /**
     * Kiểm chuyển trạng thái bằng domain và lưu trong transaction REQUIRED.
     *
     * <p>Không tự thử lại khi trạng thái cũ đã thay đổi; không tính lại hạn giữ chỗ.
     *
     * @param command yêu cầu đã kiểm tra mã khóa lịch
     * @throws DomainException nếu thiếu đầu vào, không tìm thấy, vi phạm điều kiện hoặc thua tranh chấp
     */
    void confirm(ConfirmReservationCommand command);
}
