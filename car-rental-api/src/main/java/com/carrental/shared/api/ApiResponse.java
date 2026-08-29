package com.carrental.shared.api;

/**
 * Đóng gói kết quả API theo cấu trúc thống nhất: success, data và error.
 *
 * <p>Response thành công không chứa lỗi. Response thất bại phải chứa
 * thông tin lỗi và không được chứa dữ liệu.
 *
 * @param <T> kiểu dữ liệu trả về khi yêu cầu thành công
 * @param success cho biết yêu cầu có thành công hay không
 * @param data dữ liệu trả về; luôn null khi thất bại
 * @param error thông tin lỗi; luôn null khi thành công
 */
public record ApiResponse<T>(
        boolean success,
        T data,
        ApiError error
) {

    /**
     * Kiểm tra tính nhất quán của response, kể cả khi bên gọi sử dụng
     * constructor trực tiếp thay vì các phương thức tạo response.
     *
     * @throws IllegalArgumentException nếu response thành công chứa lỗi,
     *                                  hoặc response thất bại chứa dữ liệu
     *                                  hay thiếu thông tin lỗi
     */
    public ApiResponse {
        if (success && error != null) {
            throw new IllegalArgumentException(
                    "Successful responses must not contain an error."
            );
        }

        if (!success && (data != null || error == null)) {
            throw new IllegalArgumentException(
                    "Failed responses must contain an error and must not contain data."
            );
        }
    }

    /**
     * Tạo response thành công và đặt phần thông tin lỗi thành null.
     *
     * @param <T> kiểu dữ liệu trả về
     * @param data dữ liệu kết quả; có thể null nếu thao tác không trả dữ liệu
     * @return response có success bằng true và chứa dữ liệu được cung cấp
     */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(
                true,
                data,
                null
        );
    }

    /**
     * Tạo response thất bại từ mã lỗi ổn định và thông báo mô tả.
     *
     * <p>Phần dữ liệu luôn null. Giao diện có thể dựa vào mã lỗi
     * để lựa chọn thông báo phù hợp với ngôn ngữ của người dùng.
     *
     * @param <T> kiểu dữ liệu của response; không có giá trị dữ liệu khi thất bại
     * @param code mã lỗi để phía gọi nhận diện và xử lý
     * @param message thông báo mô tả lỗi
     * @return response có success bằng false và chứa thông tin lỗi
     * @throws IllegalArgumentException nếu mã lỗi hoặc thông báo bị trống
     */
    public static <T> ApiResponse<T> failure(
            String code,
            String message
    ) {
        return new ApiResponse<>(
                false,
                null,
                new ApiError(code, message)
        );
    }

    /**
     * Chứa thông tin lỗi công khai trả về cho phía gọi API.
     *
     * <p>Mã lỗi là hợp đồng ổn định để xử lý bằng chương trình.
     * Thông báo chỉ dùng để mô tả lỗi; không chứa stack trace,
     * câu SQL hoặc thông tin nhạy cảm của hệ thống.
     *
     * @param code mã lỗi ổn định
     * @param message thông báo mô tả lỗi
     */
    public record ApiError(
            String code,
            String message
    ) {

        /**
         * Bảo đảm thông tin lỗi có đủ mã và thông báo để phía gọi
         * nhận diện được nguyên nhân thất bại.
         *
         * @throws IllegalArgumentException nếu mã lỗi hoặc thông báo
         *                                  là null, rỗng hoặc chỉ có khoảng trắng
         */
        public ApiError {
            if (code == null || code.isBlank()) {
                throw new IllegalArgumentException(
                        "Error code must not be blank."
                );
            }

            if (message == null || message.isBlank()) {
                throw new IllegalArgumentException(
                        "Error message must not be blank."
                );
            }
        }
    }
}