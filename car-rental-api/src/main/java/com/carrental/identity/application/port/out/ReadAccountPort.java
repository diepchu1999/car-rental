package com.carrental.identity.application.port.out;

import com.carrental.identity.domain.Account;
import java.util.Optional;

/** Cổng đọc aggregate tài khoản để đối chiếu danh tính và consent theo BR-120, ADR-0006. */
public interface ReadAccountPort {
    /** Tra mã TK-<6>; chỉ trả rỗng khi không có, không nuốt lỗi truy cập dữ liệu. */
    Optional<Account> findByCode(String code);

    /** Tra subject đã được xác thực; không tra tài khoản bằng số do HTTP request tự khai. */
    Optional<Account> findBySubject(String subject);
}
