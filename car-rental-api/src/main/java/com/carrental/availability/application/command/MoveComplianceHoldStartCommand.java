package com.carrental.availability.application.command;

import com.carrental.shared.validation.Validations;

import java.time.Instant;

/**
 * Yêu cầu dời điểm bắt đầu khóa giấy tờ theo BR-015, chỉ gửi khi giấy tờ được gia hạn.
 *
 * @param code mã khóa lịch đang tồn tại
 * @param newStartInclusive mốc hết hạn mới; domain kiểm phải sau mốc đang lưu
 */
public record MoveComplianceHoldStartCommand(String code, Instant newStartInclusive) {

    /** Kiểm dữ liệu bắt buộc, giữ nguyên mã và mốc do bên gọi cung cấp. */
    public MoveComplianceHoldStartCommand {
        code = Validations.requiredText(code, "code");
        newStartInclusive = Validations.required(newStartInclusive, "newStartInclusive");
    }

    /** Tạo yêu cầu từ giá trị thô, không nhận DTO hoặc tự lấy thời gian hệ thống. */
    public static MoveComplianceHoldStartCommand from(String code, Instant newStartInclusive) {
        return new MoveComplianceHoldStartCommand(code, newStartInclusive);
    }
}
