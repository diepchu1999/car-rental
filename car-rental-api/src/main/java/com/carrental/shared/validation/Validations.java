package com.carrental.shared.validation;

import com.carrental.shared.error.DomainException;

/**
 * Cung cấp các phép kiểm tra đầu vào dùng chung cho các module.
 *
 * <p>Lớp thuần Java, không phụ thuộc Spring hoặc giao thức HTTP.
 * Bên gọi quyết định trường nào cần kiểm tra theo hợp đồng đầu vào.
 *
 * <p>Các phương thức giữ nguyên giá trị hợp lệ, không tự cắt khoảng
 * trắng, đổi chữ hoa thường hoặc chuẩn hóa dữ liệu.
 *
 * <p>Tên trường phải là hằng nội bộ của ứng dụng, không lấy từ request.
 * Thông báo lỗi không đưa giá trị người dùng nhập vào phản hồi.
 */
public final class Validations {

    /**
     * Ngăn khởi tạo lớp tiện ích chỉ chứa các phương thức static.
     */
    private Validations() {
    }

    /**
     * Kiểm giá trị của một trường bắt buộc không phải là null.
     *
     * <p>Phương thức chỉ kiểm null. Các giá trị như số 0, false
     * hoặc chuỗi rỗng vẫn được trả về nguyên trạng.
     * Với chuỗi bắt buộc có nội dung, dùng requiredText.
     *
     * @param value giá trị cần kiểm tra
     * @param fieldName tên trường nội bộ dùng trong thông báo lỗi
     * @param <T> kiểu dữ liệu của giá trị cần kiểm tra
     * @return chính giá trị đã nhận nếu không phải null
     * @throws DomainException nếu giá trị là null
     * @throws IllegalArgumentException nếu tên trường bị trống
     */
    public static <T> T required(T value, String fieldName) {
        validateFieldName(fieldName);

        if (value == null) {
            throw DomainException.invalidInput(
                    fieldName + " is required."
            );
        }

        return value;
    }

    /**
     * Kiểm chuỗi bắt buộc có nội dung, không phải null hoặc chỉ
     * chứa các ký tự khoảng trắng theo String.isBlank().
     *
     * <p>Chuỗi hợp lệ được giữ nguyên, kể cả khoảng trắng đầu
     * và cuối. Phương thức không kiểm định dạng hoặc độ dài.
     *
     * @param value chuỗi cần kiểm tra
     * @param fieldName tên trường nội bộ dùng trong thông báo lỗi
     * @return chính chuỗi đã nhận nếu có nội dung
     * @throws DomainException nếu chuỗi là null, rỗng
     *                         hoặc chỉ chứa khoảng trắng
     * @throws IllegalArgumentException nếu tên trường bị trống
     */
    public static String requiredText(String value, String fieldName) {
        String requiredValue = required(value, fieldName);

        if (requiredValue.isBlank()) {
            throw DomainException.invalidInput(
                    fieldName + " must not be blank."
            );
        }

        return requiredValue;
    }

    /**
     * Kiểm tên trường mà code bên gọi cung cấp cho tiện ích.
     *
     * <p>Tên trường sai là lỗi lập trình, không phải lỗi dữ liệu
     * của người dùng, nên không ném DomainException.
     *
     * @param fieldName tên trường nội bộ cần kiểm tra
     * @throws IllegalArgumentException nếu tên trường là null,
     *                                  rỗng hoặc chỉ chứa khoảng trắng
     */
    private static void validateFieldName(String fieldName) {
        if (fieldName == null || fieldName.isBlank()) {
            throw new IllegalArgumentException(
                    "fieldName must not be blank."
            );
        }
    }
}