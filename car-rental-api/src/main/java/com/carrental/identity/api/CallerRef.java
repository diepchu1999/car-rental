package com.carrental.identity.api;

import com.carrental.identity.domain.AccountRole;
import com.carrental.identity.domain.AccountRoles;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Danh tính tối thiểu cho use case của các module theo BR-803/809/810.
 * Chỉ dựng từ nguồn đã xác thực; không nhận vai hoặc chi nhánh do HTTP body/header tự khai.
 * Hợp đồng công khai chỉ dùng kiểu Java, không buộc module khác import identity.domain hay Security.
 * Không chứa token, subject Keycloak hoặc số điện thoại; không phải response REST.
 *
 * @param accountCode mã tài khoản TK-<6>
 * @param roles tên vai chuẩn BR-801, bất biến và tối đa một vai nhân viên
 * @param branchId ID nội bộ của chi nhánh, null nếu chưa gán hoặc vai không dùng phạm vi chi nhánh
 */
public record CallerRef(String accountCode, Set<String> roles, Long branchId) {
    /**
     * Kiểm cấu trúc của dữ liệu nội bộ, không tự cấp vai hoặc đoán chi nhánh khi thiếu.
     * Nhân viên chưa có chi nhánh vẫn biểu diễn được để đọc tài khoản của mình;
     * use case theo chi nhánh phải từ chối riêng bằng STAFF_BRANCH_REQUIRED.
     */
    public CallerRef {
        Objects.requireNonNull(accountCode, "accountCode must not be null.");
        if (!accountCode.matches("TK-[A-Z0-9]{6}")) {
            throw new IllegalArgumentException("accountCode must be a valid account business code.");
        }
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles must not be null."));
        new AccountRoles(roles.stream().map(AccountRole::valueOf).collect(Collectors.toSet()));
        if (branchId != null && branchId <= 0) {
            throw new IllegalArgumentException("branchId must be positive when present.");
        }
    }

    /** Kiểm đúng tên vai, không có kế thừa quyền ngầm giữa các vai. */
    public boolean hasRole(String role) {
        return roles.contains(Objects.requireNonNull(role, "role must not be null."));
    }

    /** Chỉ in mã tài khoản, không tự đưa tập vai hoặc phạm vi chi nhánh vào log. */
    @Override
    public String toString() { return "CallerRef[accountCode=" + accountCode + "]"; }
}
