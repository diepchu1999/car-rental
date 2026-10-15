package com.carrental.identity.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Tập vai bất biến đã chuyển từ nguồn danh tính, không phải dữ liệu do request tự khai.
 *
 * <p>BR-809 cho phép CUSTOMER cùng một vai nhân viên, không tự chọn vai mạnh nhất
 * khi nguồn danh tính gán nhiều vai nhân viên. Tập rỗng không cấp bất kỳ quyền nào;
 * kiểm quyền cụ thể thuộc use case. Kiểu này không xác minh JWT và không lưu vai vào CSDL.
 *
 * @param values các vai đã nhận diện; không chứa null
 */
public record AccountRoles(Set<AccountRole> values) {

    /**
     * Sao chép tập đầu vào và ép tối đa một vai nhân viên theo BR-809.
     *
     * @throws NullPointerException nếu tập hoặc phần tử là null
     * @throws IllegalArgumentException nếu có nhiều vai nhân viên; biên xác thực phải từ chối
     *         nguồn danh tính này, không được tự bỏ bớt vai để tiếp tục
     */
    public AccountRoles {
        values = Set.copyOf(Objects.requireNonNull(values, "Roles must not be null."));
        if (values.stream().filter(AccountRole::isEmployee).count() > 1) {
            throw new IllegalArgumentException("An account must not have multiple employee roles.");
        }
    }

    /** Kiểm có một vai chính xác; không có quan hệ kế thừa quyền ngầm giữa các vai. */
    public boolean has(AccountRole role) {
        return values.contains(Objects.requireNonNull(role, "Role must not be null."));
    }

    /** Trả vai nhân viên duy nhất nếu có; khách hoặc đối tác không có vai nhân viên. */
    public Optional<AccountRole> employeeRole() {
        return values.stream().filter(AccountRole::isEmployee).findFirst();
    }
}
