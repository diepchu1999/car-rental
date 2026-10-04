package com.carrental.availability.application.port.in;

import com.carrental.availability.application.command.MoveComplianceHoldStartCommand;

/** Cổng nội bộ dời điểm bắt đầu khóa giấy tờ trên chính bản ghi cũ theo BR-015. */
public interface MoveComplianceHoldStartUseCase {

    /**
     * Chỉ gọi khi giấy tờ được gia hạn; không hoàn tất, nhả hoặc tạo lại khóa.
     * Giữ nguyên trạng thái và status_changed_at; tranh chấp mốc cũ trả CONFLICT.
     *
     * @param command mã khóa và mốc bắt đầu mới đã kiểm dữ liệu bắt buộc
     */
    void moveComplianceHoldStart(MoveComplianceHoldStartCommand command);
}
