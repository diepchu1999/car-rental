package com.carrental.keycloak.authentication;

/** Từ chối tài khoản không còn dùng được mà không tiết lộ số điện thoại hay trạng thái nội bộ. */
final class CustomerAccountUnavailableException extends RuntimeException {
    /** Thông báo không chứa danh tính và không được dùng như mã lỗi API backend. */
    CustomerAccountUnavailableException() { super("Customer account is unavailable."); }
}
