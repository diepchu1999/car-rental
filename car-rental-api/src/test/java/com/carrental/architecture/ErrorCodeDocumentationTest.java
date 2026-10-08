package com.carrental.architecture;

import com.carrental.architecture.fixtures.errorcode.UndocumentedErrorCode;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Khóa hợp đồng mã lỗi theo backend-guideline §5, không yêu cầu tài liệu và code bằng nhau. */
class ErrorCodeDocumentationTest {
    private static final String TABLE_HEADER = """
            ## 5. Mã lỗi

            | Mã | HTTP | Khi nào |
            |---|---|---|
            """;

    /** Enum production phải là tập con của bảng thật được commit cùng repo. */
    @Test
    void productionErrorCodesAreDocumented() {
        ErrorCodeDocumentation.assertDocumented(ErrorCode.class,
                ErrorCodeDocumentation.readCodes(ErrorCodeDocumentation.locateGuideline()));
    }

    /** Fixture âm gây đúng AssertionError và báo chính xác mã thiếu, không nhầm mã đã có. */
    @Test
    void rejectsUndocumentedEnumFixture() {
        var documented = ErrorCodeDocumentation.readCodes(ErrorCodeDocumentation.locateGuideline());
        var failure = assertThrowsExactly(AssertionError.class,
                () -> ErrorCodeDocumentation.assertDocumented(UndocumentedErrorCode.class, documented));
        assertTrue(failure.getMessage().contains(UndocumentedErrorCode.class.getName()));
        assertTrue(failure.getMessage().contains("[FAKE_UNDOCUMENTED_ERROR]"));
        assertTrue(failure.getMessage().contains("backend-guideline.md section 5"));
    }

    /** Tài liệu có thêm mã dành cho module tương lai vẫn hợp lệ, không ép thêm hằng enum. */
    @Test
    void permitsDocumentedCodesNotUsedByTheEnum() {
        var documented = ErrorCodeDocumentation.readCodes(ErrorCodeDocumentation.locateGuideline());
        assertTrue(documented.size() > DocumentedErrorCode.values().length);
        assertDoesNotThrow(() -> ErrorCodeDocumentation.assertDocumented(DocumentedErrorCode.class, documented));
    }

    /** Không tính mã trong đoạn văn, cột mô tả, bảng khác hoặc mục khác; gộp mã có nhiều HTTP. */
    @Test
    void readsOnlyFirstColumnOfTheSectionFiveErrorTable() {
        String markdown = """
                ## 4. Other section
                | `WRONG_SECTION` | 400 | Ignored |
                """ + TABLE_HEADER + """
                | `INVALID_REQUEST` | 400 | Mentions `FAKE_UNDOCUMENTED_ERROR` only |
                | `INVALID_REQUEST` | 422 | Same code with another HTTP status |
                | `INTERNAL_ERROR` | 500 | Unexpected failure |

                Text mentions `NOT_A_TABLE_CODE`.
                | Other table | Value |
                |---|---|
                | `WRONG_TABLE` | Ignored |

                ## 6. Testing
                | `ANOTHER_SECTION` | 400 | Ignored |
                """;
        assertEquals(Set.of("INVALID_REQUEST", "INTERNAL_ERROR"), ErrorCodeDocumentation.parseCodes(markdown));
    }

    /** Không tìm thấy mục chuẩn phải báo lỗi thay vì quét toàn tài liệu tìm chuỗi giống mã. */
    @Test
    void rejectsMissingSection() {
        var failure = assertThrowsExactly(IllegalStateException.class,
                () -> ErrorCodeDocumentation.parseCodes("## 6. Testing\n| `INVALID_REQUEST` | 400 | Input |"));
        assertTrue(failure.getMessage().contains("Missing section"));
    }

    /** Bảng thiếu hoặc đặt ở mục sau không được làm phép kiểm xanh giả. */
    @Test
    void rejectsMissingTableInSectionFive() {
        var failure = assertThrowsExactly(IllegalStateException.class,
                () -> ErrorCodeDocumentation.parseCodes("## 5. Mã lỗi\nNo table.\n## 6. Testing\n"
                        + TABLE_HEADER.substring(TABLE_HEADER.indexOf("|"))
                        + "| `INVALID_REQUEST` | 400 | Input |\n"));
        assertTrue(failure.getMessage().contains("table header/separator"));
    }

    /** Có tiêu đề nhưng không có hàng dữ liệu là lỗi cấu trúc, không phải tập mã hợp lệ rỗng. */
    @Test
    void rejectsEmptyTable() {
        var failure = assertThrowsExactly(IllegalStateException.class,
                () -> ErrorCodeDocumentation.parseCodes(TABLE_HEADER));
        assertTrue(failure.getMessage().contains("table in section 5 is empty"));
    }

    /** Hàng hỏng phải báo dòng cần sửa, không âm thầm bỏ qua rồi chỉ kiểm những hàng còn lại. */
    @Test
    void rejectsMalformedRow() {
        var failure = assertThrowsExactly(IllegalStateException.class,
                () -> ErrorCodeDocumentation.parseCodes(TABLE_HEADER
                        + "| INVALID_REQUEST | 400 | Missing code backticks |\n"));
        assertTrue(failure.getMessage().contains("Malformed error-code table row at line 5"));
    }

    /** File không đọc được phải giữ nguyên nguyên nhân IO và đường dẫn trong thông báo. */
    @Test
    void rejectsMissingFile(@TempDir Path temporaryDirectory) {
        Path missing = temporaryDirectory.resolve("missing-backend-guideline.md");
        var failure = assertThrowsExactly(IllegalStateException.class,
                () -> ErrorCodeDocumentation.readCodes(missing));
        assertTrue(failure.getMessage().contains(missing.toString()));
        assertInstanceOf(java.io.IOException.class, failure.getCause());
    }

    /** Fixture hợp lệ cố ý chỉ có một mã, còn bảng chuẩn được phép có nhiều mã hơn. */
    private enum DocumentedErrorCode {
        /** Đã được tài liệu hoá trong bảng §5. */
        INVALID_REQUEST
    }
}
