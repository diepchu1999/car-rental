package com.carrental.identity.application.port.in;

import com.carrental.identity.application.view.CurrentAccountDetail;

/** BR-120/803/809: đọc tài khoản của chính người gọi, dùng chung cho hai audience client và admin. */
public interface GetCurrentAccountUseCase {
    /**
     * Không nhận mã hoặc số điện thoại đầu vào để không mở đường đọc tài khoản người khác.
     * Danh tính lấy qua CallerDirectory; chưa gán chi nhánh vẫn được đọc tài khoản của mình.
     * Ngoài request HTTP phải thất bại rõ ràng, không giả danh tài khoản hệ thống.
     */
    CurrentAccountDetail get();
}
