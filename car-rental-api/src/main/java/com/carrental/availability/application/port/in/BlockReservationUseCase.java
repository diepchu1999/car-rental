package com.carrental.availability.application.port.in;

import com.carrental.availability.api.ReservationRef;
import com.carrental.availability.application.command.BlockReservationCommand;
import com.carrental.shared.error.DomainException;

/**
 * Cổng nội bộ tạo khóa vận hành theo BR-007, BR-011, BR-012, BR-015 và BR-104.
 *
 * <p>Module khác chỉ gọi AvailabilityDirectory; không import trực tiếp cổng này.
 */
public interface BlockReservationUseCase {

    /**
     * Ghi thẳng BLOCKED trong transaction REQUIRED, không kiểm xe trống trước hoặc tự cộng đệm.
     *
     * @param command yêu cầu khóa vận hành đã kiểm tra
     * @return định danh khóa vừa chèn, chưa có nghĩa transaction bên gọi đã commit
     * @throws DomainException nếu đầu vào sai hoặc lịch chồng khóa đang chặn
     * @throws IllegalStateException nếu hết năm lần thử mã nghiệp vụ
     */
    ReservationRef block(BlockReservationCommand command);
}
