package com.carrental.vehicle.adapter.in.rest.admin.request;

import java.math.BigDecimal;

/**
 * Đầu vào HTTP đổi giá BR-234; giữ số chính xác, không cắt phần lẻ trước validation.
 * @param dailyRate giá do client cung cấp, command/domain kiểm tra kể cả trường hợp thiếu
 */
public record ChangeVehicleDailyRateRequest(BigDecimal dailyRate) {
}
