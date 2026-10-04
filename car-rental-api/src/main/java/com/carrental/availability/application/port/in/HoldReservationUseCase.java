package com.carrental.availability.application.port.in;

import com.carrental.availability.api.ReservationRef;
import com.carrental.availability.application.command.HoldReservationCommand;
import com.carrental.shared.error.DomainException;

/**
 * Cổng đầu vào nội bộ cho giữ chỗ theo BR-102, BR-103 và BR-104.
 *
 * <p>Module khác vẫn chỉ được gọi AvailabilityDirectory trong api,
 * không import trực tiếp use case hoặc command này theo ADR-0008.
 */
public interface HoldReservationUseCase {

    /**
     * Giữ chỗ với thời hạn hệ thống, cộng đệm và để PostgreSQL chống trùng lịch.
     *
     * <p>Tham gia transaction bên gọi nếu có. Kết quả không cam kết
     * transaction ngoài đã commit; rollback ngoài phải hủy cả chỗ giữ.
     *
     * @param command yêu cầu đã kiểm tra, không được null
     * @return ID và mã của khóa lịch vừa được chèn
     * @throws DomainException nếu đầu vào sai hoặc xe đã bận
     * @throws IllegalStateException nếu hết năm lần thử sinh mã duy nhất
     */
    ReservationRef hold(HoldReservationCommand command);
}
