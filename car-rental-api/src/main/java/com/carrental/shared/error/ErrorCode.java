package com.carrental.shared.error;

/**
 * Định nghĩa mã lỗi ổn định dùng chung cho API chi nhánh và xe.
 *
 * <p>Tên hằng enum được trả trong trường error.code của response.
 * Phía gọi có thể dựa vào mã này để xử lý lỗi hoặc chọn thông báo
 * phù hợp với ngôn ngữ của người dùng.
 *
 * <p>Không đổi tên mã đã phát hành. Thông báo mặc định chỉ mô tả lỗi;
 * phía gọi không nên dùng nội dung thông báo để quyết định cách xử lý.
 *
 * <p>Enum giữ thuần Java. Việc chuyển lỗi thành mã HTTP thuộc lớp
 * xử lý lỗi ở biên API.
 */
public enum ErrorCode {

    /**
     * Request sai định dạng hoặc thiếu dữ liệu đầu vào bắt buộc.
     * Đây là lỗi hợp đồng API, chưa phải lỗi quy tắc nghiệp vụ.
     */
    INVALID_REQUEST(
            "The request contains invalid or missing input."
    ),

    /**
     * Không tìm thấy chi nhánh theo mã nghiệp vụ.
     * Dùng khi đọc chi nhánh hoặc tra cứu chi nhánh để tạo xe theo BR-003.
     */
    BRANCH_NOT_FOUND(
            "Branch not found."
    ),

    /**
     * Không tìm thấy xe theo mã nghiệp vụ khi đọc hoặc thực hiện
     * một thao tác trên xe.
     */
    VEHICLE_NOT_FOUND(
            "Vehicle not found."
    ),

    /**
     * Biển số đã thuộc về một xe khác.
     * Tương ứng ràng buộc UNIQUE trên biển số theo database-guideline §8.
     */
    VEHICLE_PLATE_ALREADY_EXISTS(
            "A vehicle with this plate number already exists."
    ),

    /**
     * Trạng thái hiện tại của xe không cho phép thao tác được yêu cầu.
     * Áp dụng luồng gửi duyệt và duyệt theo status-flow §4 và BR-010.
     */
    VEHICLE_INVALID_STATUS_TRANSITION(
            "The requested vehicle status transition is not allowed."
    ),

    /**
     * Thiếu ngày hết hạn của đăng kiểm hoặc bảo hiểm TNDS khi duyệt xe.
     * BR-005 yêu cầu có đủ hai giấy tờ tại cổng duyệt.
     */
    VEHICLE_DOCUMENT_MISSING(
            "Vehicle inspection and compulsory liability insurance expiry dates are required for approval."
    ),

    /**
     * Đăng kiểm hoặc bảo hiểm TNDS đã hết hạn.
     * Trong luồng duyệt xe, BR-005 yêu cầu từ chối duyệt trường hợp này.
     */
    VEHICLE_DOCUMENT_EXPIRED(
            "Vehicle inspection or compulsory liability insurance has expired."
    ),

    /**
     * Lỗi hệ thống ngoài dự kiến.
     * Thông báo công khai không tiết lộ câu SQL, stack trace hoặc secret.
     */
    INTERNAL_ERROR(
            "An unexpected error occurred."
    );

    private final String defaultMessage;

    /**
     * Gắn thông báo tiếng Anh mặc định với một mã lỗi.
     *
     * @param defaultMessage thông báo công khai mô tả lỗi
     */
    ErrorCode(String defaultMessage) {
        this.defaultMessage = defaultMessage;
    }

    /**
     * Trả thông báo mặc định để sử dụng khi tạo response lỗi.
     *
     * @return thông báo tiếng Anh tương ứng với mã lỗi
     */
    public String defaultMessage() {
        return defaultMessage;
    }
}