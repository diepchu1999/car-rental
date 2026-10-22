package com.carrental.identity.application.port.out;

import com.carrental.identity.domain.AccountRoles;
import java.util.Optional;

/** Cổng chỉ đọc vai hiện tại ở nguồn danh tính Keycloak (BR-801/809, ADR-0006), không cấp hoặc đổi vai. */
public interface ReadAccountRolesPort {
    /**
     * Tra bằng subject đã lưu trong tài khoản nghiệp vụ, không dùng số hoặc vai do payload tự khai.
     * Trả rỗng chỉ khi người dùng không tồn tại; tập vai rỗng nghĩa là tồn tại nhưng không có vai dự án.
     * Lỗi kết nối, xác thực client kỹ thuật hoặc dữ liệu vai bất hợp lệ phải được báo, không giả thành rỗng.
     * Adapter chỉ trả các vai BR-801 và phải từ chối nhiều vai nhân viên, không chọn vai mạnh nhất.
     */
    Optional<AccountRoles> findBySubject(String keycloakSubject);
}
