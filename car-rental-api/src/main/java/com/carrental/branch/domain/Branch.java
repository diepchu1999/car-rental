package com.carrental.branch.domain;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.validation.Validations;

import java.util.regex.Pattern;

/**
 * Biểu diễn danh tính nghiệp vụ và vị trí của một chi nhánh.
 *
 * <p>Theo BR-003, xe công ty thuộc chi nhánh và sử dụng vị trí
 * chi nhánh khi tìm kiếm.
 *
 * <p>Mã nghiệp vụ theo database-guideline mục 2 được application
 * cung cấp. Domain không tự sinh mã hoặc truy cập CSDL.
 *
 * <p>Khóa số do PostgreSQL cấp khi lưu không nằm trong đối tượng
 * này. Không dùng id null hoặc id bằng 0 để biểu diễn chưa lưu.
 *
 * @param code mã chi nhánh gồm CN- và sáu chữ cái ASCII viết hoa hoặc chữ số
 * @param location tọa độ chi nhánh đã được kiểm tra hợp lệ
 * @param name tên chi nhánh bắt buộc theo BR-808, giữ nguyên cách viết
 * @param address địa chỉ bắt buộc theo BR-808, giữ nguyên cách viết
 */
public record Branch(
        String code,
        BranchLocation location,
        String name,
        String address
) {

    private static final Pattern CODE_PATTERN =
            Pattern.compile("CN-[A-Z0-9]{6}");

    /**
     * Bảo đảm chi nhánh có mã, vị trí hợp lệ và tên/địa chỉ bắt buộc theo BR-808.
     *
     * <p>Giữ nguyên dữ liệu được cung cấp, không tự cắt khoảng trắng
     * hoặc sửa chữ thường thành chữ hoa.
     *
     * <p>BranchLocation đã bảo vệ giới hạn tọa độ nên không
     * lặp lại việc kiểm từng thành phần tại đây.
     *
     * @throws DomainException nếu mã bị thiếu, trống, sai định dạng
     *                         hoặc vị trí null, tên/địa chỉ thiếu hay trắng
     */
    public Branch {
        code = Validations.requiredText(code, "code");

        if (!CODE_PATTERN.matcher(code).matches()) {
            throw DomainException.invalidInput(
                    "code must contain CN- followed by six uppercase ASCII letters or digits."
            );
        }

        location = Validations.required(location, "location");
        name = Validations.requiredText(name, "name");
        address = Validations.requiredText(address, "address");
    }
}
