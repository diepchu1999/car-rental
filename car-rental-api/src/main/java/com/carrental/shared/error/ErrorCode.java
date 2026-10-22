package com.carrental.shared.error;

/**
 * Định nghĩa mã lỗi ổn định dùng chung cho các module của ứng dụng.
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

    /** ADR-0006: thiếu token hoặc token không vượt qua kiểm chứng của resource server. */
    UNAUTHENTICATED("Authentication is required."),

    /** BR-803/809/810: người gọi không đủ vai, phạm vi hoặc mức xác thực cho thao tác. */
    ACCESS_DENIED("Access is denied."),

    /** BR-803: vai thuộc chi nhánh chưa được gán chi nhánh để thao tác. */
    STAFF_BRANCH_REQUIRED("A branch assignment is required for this employee."),

    /** ADR-0006: vai hiện tại không khớp vai ghi nhận khi gán chi nhánh. */
    STAFF_ROLE_MISMATCH("The employee role does not match the branch assignment."),

    /** BR-120/124: không tự đổi số điện thoại của tài khoản từ một token khác số đã lưu. */
    ACCOUNT_IDENTITY_MISMATCH("The account identity does not match the authenticated identity."),

    /** BR-120: số điện thoại đã gắn với một định danh Keycloak khác. */
    ACCOUNT_IDENTITY_CONFLICT("The authenticated identity conflicts with an existing account."),

    /** Không tìm thấy tài khoản theo mã nghiệp vụ TK-<6>. */
    ACCOUNT_NOT_FOUND("Account not found."),

    /** BR-807: CSDL từ chối gán quản lý thứ hai cho cùng một chi nhánh. */
    BRANCH_MANAGER_ALREADY_ASSIGNED("The branch already has a different manager."),

    /** BR-803: không gán chi nhánh cho vai không thuộc phạm vi một chi nhánh. */
    STAFF_ROLE_NOT_BRANCH_SCOPED("The employee role is not branch-scoped."),

    /** BR-120: khách thiếu bằng chứng đồng ý điều khoản hợp lệ. */
    CONSENT_REQUIRED("Valid consent to the terms is required."),

    /** Báo giá không tồn tại khi đọc theo BR-122. */
    QUOTE_NOT_FOUND("The requested quote was not found."),

    /** BR-125: lát cắt báo giá chưa hỗ trợ có tài xế. */
    QUOTE_DRIVE_MODE_NOT_SUPPORTED("Quotes with a driver are not supported yet."),

    /** BR-125: lát cắt báo giá chưa hỗ trợ giao xe tận nơi. */
    QUOTE_PICKUP_METHOD_NOT_SUPPORTED("Delivery quotes are not supported yet."),

    /** BR-218: không tính giá ngày thường cho khoảng thuê vượt lịch lễ đã khai báo. */
    HOLIDAY_CALENDAR_NOT_DECLARED("The holiday calendar has not been declared through the rental return date."),

    /** BR-125: lát cắt báo giá hiện chưa hỗ trợ gói tháng. */
    QUOTE_RENTAL_TYPE_NOT_SUPPORTED("Monthly rental quotes are not supported yet."),

    /** BR-234: phải có giá ngày trước khi duyệt xe. */
    VEHICLE_DAILY_RATE_REQUIRED("A daily rate is required before vehicle approval."),

    /** BR-234: giá ngày nguyên VND, dương và chia hết cho 1.000. */
    VEHICLE_INVALID_DAILY_RATE("Vehicle daily rate must be a positive integer multiple of 1000 VND."),

    /** BR-125: lát cắt 1 chưa hỗ trợ tìm xe theo gói tháng. */
    SEARCH_RENTAL_TYPE_NOT_SUPPORTED("Monthly rental search is not supported yet."),

    /** BR-125: lát cắt 1 chưa hỗ trợ tìm xe có tài xế. */
    SEARCH_DRIVE_MODE_NOT_SUPPORTED("Search with a driver is not supported yet."),

    /** BR-125: lát cắt 1 chưa hỗ trợ tìm xe giao tận nơi. */
    SEARCH_PICKUP_METHOD_NOT_SUPPORTED("Search with vehicle delivery is not supported yet."),

    /** BR-126: chỉ hỗ trợ sắp xếp gần nhất khi chưa có pricing và đánh giá. */
    SEARCH_SORT_NOT_SUPPORTED("Only nearest-first search is currently supported."),

    /** Gói giờ ngắn hơn tối thiểu 4 giờ theo BR-113. */
    RENTAL_DURATION_TOO_SHORT("The rental duration is shorter than the required minimum."),

    /** Giờ nhận hoặc trả xe thực tế nằm ngoài giờ chi nhánh theo BR-119. */
    OUTSIDE_BRANCH_HOURS("Pickup and return must be within branch opening hours."),

    /** Thời điểm nhận xe vi phạm cửa sổ đặt trước theo BR-121. */
    BOOKING_WINDOW_VIOLATION("Pickup is outside the allowed advance booking window."),

    /** Số chỗ không thuộc tập 4, 5, 7, 16 theo BR-018. */
    VEHICLE_INVALID_SEATS("Vehicle seats must be one of 4, 5, 7 or 16."),

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
     * Khoảng yêu cầu chồng lên một khóa lịch đang chặn của xe.
     *
     * <p>Theo BR-104 và ADR-0005, lỗi này chỉ được xác định
     * từ vi phạm ràng buộc chống chồng lịch của PostgreSQL.
     * Không dùng cho lỗi toàn vẹn dữ liệu khác.
     */
    VEHICLE_NOT_AVAILABLE(
            "The vehicle is not available for the requested period."
    ),

    /**
     * Không tìm thấy khóa lịch theo mã reservation.
     *
     * <p>Mọi chuyển trạng thái dùng mã reservation để xác định
     * đúng bản ghi, không dùng mã đơn thuê thay thế.
     */
    RESERVATION_NOT_FOUND(
            "Reservation not found."
    ),

    /**
     * Trạng thái khóa lịch không cho phép thao tác được yêu cầu.
     *
     * <p>Áp dụng máy trạng thái trong status-flow §2.
     * Cũng dùng khi cập nhật có điều kiện thất bại vì trạng thái
     * đã bị thay đổi bởi một thao tác đồng thời.
     */
    RESERVATION_INVALID_STATUS_TRANSITION(
            "The requested reservation status transition is not allowed."
    ),

    /**
     * Chỗ giữ đã hết hạn và không còn được phép xác nhận theo BR-103.
     *
     * <p>Thông báo không đóng cứng số phút vì thời hạn giữ chỗ
     * là tham số cấu hình theo BR-225.
     */
    HOLD_EXPIRED(
            "The reservation hold has expired."
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
