package com.carrental.identity.application.view;

import java.time.Instant;

/**
 * Kết quả đọc lại sau gán chi nhánh theo BR-803/809.
 * Mã chi nhánh được tra qua branch.api theo ADR-0008, không đưa ID số ra biên REST.
 *
 * @param accountCode mã tài khoản đã được gán
 * @param branchCode mã chi nhánh của bản gán đã lưu
 * @param staffRole tên vai lấy từ bản gán đã lưu, không phải quyền mới cấp cho tài khoản
 * @param assignedAt thời điểm gán đọc từ CSDL
 */
public record StaffBranchAssignmentDetail(String accountCode, String branchCode, String staffRole, Instant assignedAt) { }
