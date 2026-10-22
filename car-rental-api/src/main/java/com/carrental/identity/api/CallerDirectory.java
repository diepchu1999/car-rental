package com.carrental.identity.api;

/** Cổng lấy người gọi trong request HTTP, giữ Spring/Servlet/Security bên ngoài application (R11). */
public interface CallerDirectory {
    /**
     * Trả danh tính đã được biên xác thực thiết lập cho request hiện tại, không trả null/rỗng.
     * Có HTTP nhưng chưa xác thực: UNAUTHENTICATED. Ngoài HTTP (kể cả job): IllegalStateException.
     * Không dùng cổng này để dựng tài khoản hệ thống hoặc giả ADMIN cho tác vụ nền.
     */
    CallerRef current();
}
