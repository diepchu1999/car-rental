package com.carrental.vehicle.api;

/**
 * Thuộc tính tối thiểu cho tạo đơn; không đưa domain hoặc giá hiện tại sang booking (ADR-0008/0014).
 * @param id định danh nội bộ để availability giữ chỗ, không trả qua REST
 * @param code mã xe để đối chiếu báo giá và đóng băng vào đơn
 * @param branchCode mã chi nhánh nhận/trả được tra qua branch.api
 * @param seats số chỗ dùng kiểm hạng GPLX (BR-128)
 * @param transmission hộp số dùng kiểm hạn chế chỉ số tự động (BR-128)
 */
public record VehicleRef(long id, String code, String branchCode, int seats, String transmission) { }
