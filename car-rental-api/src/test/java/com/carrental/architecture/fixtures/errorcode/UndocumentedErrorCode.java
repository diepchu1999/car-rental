package com.carrental.architecture.fixtures.errorcode;

/** Enum cố ý sai để chứng minh bộ kiểm không bỏ lọt mã chưa có trong bảng chuẩn. */
public enum UndocumentedErrorCode {
    /** Mã hợp lệ giữ trong fixture để lỗi chỉ đến từ hằng còn lại. */
    INVALID_REQUEST,

    /** Chỉ dùng trong phép thử âm, không thêm mã này vào enum hoặc tài liệu production. */
    FAKE_UNDOCUMENTED_ERROR
}
