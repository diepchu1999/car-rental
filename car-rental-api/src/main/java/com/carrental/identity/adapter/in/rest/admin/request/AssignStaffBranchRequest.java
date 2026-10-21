package com.carrental.identity.adapter.in.rest.admin.request;

/**
 * BR-803/809: dữ liệu thô chỉ gồm mã chi nhánh, không nhận vai hoặc danh tính người thực hiện.
 * Mã tài khoản lấy từ path; validation do AssignStaffBranchCommand đảm nhiệm.
 */
public record AssignStaffBranchRequest(String branchCode) { }
