package com.carrental.shared.sql;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;

import java.io.FileNotFoundException;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kiểm tra bộ đọc SQL với tài nguyên dành riêng cho test.
 *
 * <p>Khởi tạo SqlLoader trực tiếp, không cần Spring context
 * hoặc kết nối CSDL. SQL mẫu chỉ được đọc, không được thực thi.
 */
class SqlLoaderTest {

    private final SqlLoader sqlLoader = new SqlLoader();

    /**
     * Kiểm nội dung UTF-8 được giữ nguyên, bao gồm comment tiếng Việt,
     * tham số SQL và ký tự xuống dòng cuối file.
     */
    @Test
    void readsUtf8SqlWithoutChangingContent() {
        String expectedSql = """
                -- Kiểm tra đọc SQL bằng UTF-8.
                SELECT :branchCode AS branch_code;
                """;

        String actualSql = sqlLoader.load("sql/sql_loader/valid.sql");

        assertEquals(expectedSql, actualSql);
    }

    /**
     * Kiểm file không tồn tại gây lỗi rõ đường dẫn và giữ lại
     * nguyên nhân gốc để hỗ trợ chẩn đoán.
     */
    @Test
    void rejectsMissingResource() {
        String resourcePath = "sql/sql_loader/missing.sql";

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> sqlLoader.load(resourcePath)
        );

        assertEquals(
                "Failed to read SQL resource from classpath: " + resourcePath,
                exception.getMessage()
        );
        assertInstanceOf(
                FileNotFoundException.class,
                exception.getCause()
        );
    }

    /**
     * Kiểm file hoàn toàn rỗng bị từ chối.
     *
     * <p>Kiểm kích thước trước để bảo đảm fixture thật sự có 0 byte.
     *
     * @throws IOException nếu không đọc được thông tin tài nguyên kiểm thử
     */
    @Test
    void rejectsEmptyResource() throws IOException {
        String resourcePath = "sql/sql_loader/empty.sql";

        assertEquals(
                0L,
                new ClassPathResource(resourcePath).contentLength(),
                "Empty fixture must contain no bytes."
        );

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> sqlLoader.load(resourcePath)
        );

        assertEquals(
                "SQL resource must not be blank: " + resourcePath,
                exception.getMessage()
        );
    }

    /**
     * Kiểm file chỉ có khoảng trắng hoặc xuống dòng cũng bị từ chối.
     *
     * <p>Fixture phải có dữ liệu để trường hợp này không vô tình
     * trở thành một bản sao của test file rỗng.
     *
     * @throws IOException nếu không đọc được thông tin tài nguyên kiểm thử
     */
    @Test
    void rejectsWhitespaceOnlyResource() throws IOException {
        String resourcePath = "sql/sql_loader/whitespace.sql";

        assertTrue(
                new ClassPathResource(resourcePath).contentLength() > 0,
                "Whitespace fixture must not be empty."
        );

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> sqlLoader.load(resourcePath)
        );

        assertEquals(
                "SQL resource must not be blank: " + resourcePath,
                exception.getMessage()
        );
    }

    /**
     * Kiểm đường dẫn null, rỗng hoặc chỉ chứa khoảng trắng bị từ chối
     * trước khi thử đọc tài nguyên.
     *
     * @param resourcePath đường dẫn không có nội dung hợp lệ
     */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void rejectsBlankPaths(String resourcePath) {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> sqlLoader.load(resourcePath)
        );

        assertEquals(
                "SQL resource path must not be blank.",
                exception.getMessage()
        );
    }

    /**
     * Kiểm đường dẫn sai thư mục gốc, sai phần mở rộng,
     * chứa dấu chuyển lên thư mục cha hoặc gạch chéo ngược bị từ chối.
     *
     * @param resourcePath đường dẫn vi phạm quy ước tài nguyên SQL
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "branch/find_by_code.sql",
            "/sql/sql_loader/valid.sql",
            "sql/sql_loader/valid.txt",
            "sql/../sql_loader/valid.sql",
            "sql/sql_loader\\valid.sql"
    })
    void rejectsMalformedPaths(String resourcePath) {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> sqlLoader.load(resourcePath)
        );

        assertEquals(
                "SQL resource path must point to a .sql file under sql/ "
                        + "and must not contain '..' or backslashes: "
                        + resourcePath,
                exception.getMessage()
        );
    }
}