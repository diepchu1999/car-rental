package com.carrental.shared.sql;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Đọc nội dung file SQL dùng chung cho các persistence adapter.
 *
 * <p>Tài nguyên được đọc từ classpath bằng UTF-8, dùng được cả khi
 * chạy trong IDE và khi ứng dụng được đóng gói thành JAR.
 *
 * <p>Lớp này chỉ đọc nội dung, không thực thi hoặc kiểm cú pháp SQL.
 * Đường dẫn phải là hằng nội bộ của ứng dụng, không lấy từ request.
 */
@Component
public final class SqlLoader {

    /**
     * Khởi tạo bộ đọc SQL không giữ trạng thái.
     *
     * <p>Spring quản lý đối tượng này để các persistence adapter
     * nhận qua constructor. Unit test có thể khởi tạo trực tiếp.
     */
    public SqlLoader() {
    }

    /**
     * Đọc toàn bộ file SQL và giữ nguyên nội dung của file.
     *
     * <p>Đường dẫn tính từ gốc classpath, bắt đầu bằng {@code sql/},
     * kết thúc bằng {@code .sql}, không chứa {@code ..}
     * hoặc dấu gạch chéo ngược.
     *
     * <p>Adapter nên gọi phương thức này trong constructor và giữ
     * kết quả trong trường final để không đọc lại ở mỗi request.
     *
     * @param resourcePath đường dẫn tài nguyên, ví dụ
     *                     {@code sql/branch/find_by_code.sql}
     * @return nội dung SQL được giải mã bằng UTF-8
     * @throws IllegalArgumentException nếu đường dẫn không hợp lệ
     * @throws IllegalStateException nếu file không tồn tại,
     *                               không đọc được hoặc chỉ chứa khoảng trắng
     */
    public String load(String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            throw new IllegalArgumentException(
                    "SQL resource path must not be blank."
            );
        }

        if (!resourcePath.startsWith("sql/")
                || !resourcePath.endsWith(".sql")
                || resourcePath.contains("..")
                || resourcePath.contains("\\")) {
            throw new IllegalArgumentException(
                    "SQL resource path must point to a .sql file under sql/ "
                            + "and must not contain '..' or backslashes: "
                            + resourcePath
            );
        }

        ClassPathResource resource = new ClassPathResource(resourcePath);

        try (InputStream input = resource.getInputStream()) {
            String sql = new String(
                    input.readAllBytes(),
                    StandardCharsets.UTF_8
            );

            if (sql.isBlank()) {
                throw new IllegalStateException(
                        "SQL resource must not be blank: " + resourcePath
                );
            }

            return sql;
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Failed to read SQL resource from classpath: " + resourcePath,
                    exception
            );
        }
    }
}