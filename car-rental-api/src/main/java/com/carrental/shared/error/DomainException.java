package com.carrental.shared.error;

import java.io.Serial;
import java.util.Objects;

/**
 * Biểu diễn lỗi có chủ đích phát sinh khi kiểm tra đầu vào
 * hoặc thực hiện một thao tác nghiệp vụ.
 *
 * <p>Exception mang mã lỗi ổn định, nhóm lỗi và thông báo công khai.
 * Lớp xử lý API sử dụng những thông tin này để tạo response lỗi.
 *
 * <p>Lớp giữ thuần Java để domain và application có thể sử dụng.
 * Việc ánh xạ nhóm lỗi sang HTTP thuộc lớp xử lý API.
 */
public final class DomainException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;
    private final Category category;

    /**
     * Tạo exception với đầy đủ thông tin cần thiết để xử lý lỗi.
     *
     * <p>Constructor được giữ private để bên gọi thể hiện ý định
     * thông qua các phương thức tạo lỗi theo từng nhóm.
     *
     * @param errorCode mã lỗi ổn định
     * @param category nhóm lỗi theo ý nghĩa của thao tác
     * @param message thông báo công khai, không chứa thông tin nhạy cảm
     * @throws NullPointerException nếu mã lỗi hoặc nhóm lỗi là null
     * @throws IllegalArgumentException nếu thông báo bị trống
     */
    private DomainException(
            ErrorCode errorCode,
            Category category,
            String message
    ) {
        super(message);

        this.errorCode = Objects.requireNonNull(
                errorCode,
                "errorCode must not be null."
        );

        this.category = Objects.requireNonNull(
                category,
                "category must not be null."
        );

        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException(
                    "Error message must not be blank."
            );
        }
    }

    /**
     * Tạo lỗi đầu vào với thông báo cụ thể về dữ liệu không hợp lệ.
     *
     * <p>Thông báo nên chỉ rõ trường cần sửa nhưng không lặp lại
     * dữ liệu nhạy cảm mà người dùng đã gửi.
     *
     * @param message thông báo tiếng Anh mô tả lỗi đầu vào
     * @return exception thuộc nhóm đầu vào không hợp lệ
     * @throws IllegalArgumentException nếu thông báo bị trống
     */
    public static DomainException invalidInput(String message) {
        return new DomainException(
                ErrorCode.INVALID_REQUEST,
                Category.INVALID_INPUT,
                message
        );
    }

    /**
     * Tạo lỗi khi không tìm thấy tài nguyên mà thao tác yêu cầu.
     *
     * @param errorCode mã lỗi xác định loại tài nguyên không tìm thấy
     * @return exception sử dụng thông báo mặc định của mã lỗi
     * @throws NullPointerException nếu mã lỗi là null
     */
    public static DomainException notFound(ErrorCode errorCode) {
        return new DomainException(
                errorCode,
                Category.NOT_FOUND,
                errorCode.defaultMessage()
        );
    }

    /**
     * Tạo lỗi khi thao tác xung đột với dữ liệu hiện có,
     * chẳng hạn tạo xe có biển số đã được sử dụng.
     *
     * @param errorCode mã lỗi xác định loại xung đột
     * @return exception sử dụng thông báo mặc định của mã lỗi
     * @throws NullPointerException nếu mã lỗi là null
     */
    public static DomainException conflict(ErrorCode errorCode) {
        return new DomainException(
                errorCode,
                Category.CONFLICT,
                errorCode.defaultMessage()
        );
    }

    /**
     * Tạo lỗi khi thao tác vi phạm một điều kiện nghiệp vụ,
     * chẳng hạn duyệt xe có giấy tờ đã hết hạn.
     *
     * @param errorCode mã lỗi xác định quy tắc bị vi phạm
     * @return exception sử dụng thông báo mặc định của mã lỗi
     * @throws NullPointerException nếu mã lỗi là null
     */
    public static DomainException ruleViolation(ErrorCode errorCode) {
        return new DomainException(
                errorCode,
                Category.RULE_VIOLATION,
                errorCode.defaultMessage()
        );
    }

    /**
     * Trả mã lỗi để lớp xử lý API đưa vào trường error.code.
     *
     * @return mã lỗi ổn định của exception
     */
    public ErrorCode errorCode() {
        return errorCode;
    }

    /**
     * Trả nhóm lỗi để lớp xử lý API xác định cách phản hồi.
     *
     * @return nhóm lỗi theo ngữ cảnh phát sinh
     */
    public Category category() {
        return category;
    }

    /**
     * Phân loại lỗi theo ý nghĩa, độc lập với giao thức truyền tải.
     *
     * <p>Cùng một mã lỗi có thể thuộc nhóm khác nhau tùy thao tác.
     * Nhóm lỗi không chứa giá trị HTTP hoặc dependency framework.
     */
    public enum Category {

        /**
         * Dữ liệu đầu vào sai định dạng hoặc thiếu giá trị bắt buộc.
         */
        INVALID_INPUT,

        /**
         * Tài nguyên được yêu cầu không tồn tại.
         */
        NOT_FOUND,

        /**
         * Thao tác xung đột với dữ liệu hiện có.
         */
        CONFLICT,

        /**
         * Thao tác không thỏa điều kiện nghiệp vụ.
         */
        RULE_VIOLATION
    }
}