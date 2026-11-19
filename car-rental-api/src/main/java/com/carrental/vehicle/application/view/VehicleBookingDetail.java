package com.carrental.vehicle.application.view;

import com.carrental.vehicle.domain.Transmission;

/**
 * Mô hình đọc nội bộ phục vụ tạo đơn, không phải hồ sơ admin hoặc cam kết lịch trống.
 * @param id khóa xe cho availability
 * @param code mã xe
 * @param branchCode mã chi nhánh nhận/trả
 * @param seats số chỗ để kiểm GPLX
 * @param transmission hộp số để kiểm GPLX
 */
public record VehicleBookingDetail(long id, String code, String branchCode, int seats, Transmission transmission) { }
