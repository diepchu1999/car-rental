package com.carrental.availability.application.port.in;

import com.carrental.availability.application.command.CompleteReservationCommand;
import com.carrental.shared.error.DomainException;

/**
 * Cổng nội bộ để hoàn tất chuyến hoặc khóa vận hành, giữ nguyên khoảng có đệm theo BR-109, BR-116 và status-flow §2.
 *
 * <p>Module khác phải gọi AvailabilityDirectory, không import use case này.
 */
public interface CompleteReservationUseCase {

    /**
     * Kiểm chuyển trạng thái bằng domain và lưu trong transaction REQUIRED.
     *
     * <p>Không tự thử lại khi trạng thái cũ đã thay đổi; không tính lại hạn giữ chỗ.
     *
     * @param command yêu cầu đã kiểm tra mã khóa lịch
     * @throws DomainException nếu thiếu đầu vào, không tìm thấy, vi phạm điều kiện hoặc thua tranh chấp
     */
    void complete(CompleteReservationCommand command);
}
