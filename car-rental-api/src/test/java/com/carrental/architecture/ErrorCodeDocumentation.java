package com.carrental.architecture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Đối chiếu một chiều enum mã lỗi với bảng chuẩn trong backend-guideline §5. */
final class ErrorCodeDocumentation {
    private static final String DOCUMENT = "car-rental-docs/vi/architecture/backend-guideline.md";
    private static final String SECTION = "## 5. Mã lỗi";
    private static final Pattern HEADER = Pattern.compile("\\|\\s*Mã\\s*\\|\\s*HTTP\\s*\\|\\s*Khi nào\\s*\\|");
    private static final Pattern SEPARATOR = Pattern.compile("\\|\\s*:?-+:?\\s*\\|\\s*:?-+:?\\s*\\|\\s*:?-+:?\\s*\\|");
    private static final Pattern ROW = Pattern.compile("\\|\\s*`([A-Z][A-Z0-9_]*)`\\s*\\|\\s*[1-5][0-9]{2}\\s*\\|.*\\|");

    /** Tiện ích chỉ phục vụ test, không có trạng thái hoặc bản sao bảng mã viết cứng. */
    private ErrorCodeDocumentation() {
    }

    /** Tìm tài liệu ngược từ thư mục chạy Maven/IntelliJ, không phụ thuộc đường dẫn máy cá nhân. */
    static Path locateGuideline() {
        String workingDirectory = System.getProperty("user.dir");
        if (workingDirectory == null || workingDirectory.isBlank()) {
            throw new IllegalStateException("Cannot locate " + DOCUMENT + ": user.dir is missing.");
        }
        Path start = Path.of(workingDirectory).toAbsolutePath().normalize();
        for (Path current = start; current != null; current = current.getParent()) {
            Path candidate = current.resolve(DOCUMENT);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Cannot locate " + DOCUMENT + " from " + start);
    }

    /** Đọc UTF-8 từ file thật; lỗi đọc phải làm test lỗi, không được trả tập rỗng hoặc dùng mặc định. */
    static Set<String> readCodes(Path guideline) {
        try {
            return parseCodes(Files.readString(guideline, StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read error-code table from " + guideline, failure);
        }
    }

    /**
     * Chỉ nhận cột đầu của bảng Mã/HTTP/Khi nào trong §5, dừng trước mục cấp hai tiếp theo.
     * Mã lặp do có nhiều HTTP được gộp; bảng sai cấu trúc hoặc rỗng phải báo lỗi rõ ràng.
     */
    static Set<String> parseCodes(String markdown) {
        List<String> lines = markdown.lines().map(String::strip).toList();
        int sectionStart = lines.indexOf(SECTION);
        if (sectionStart < 0) {
            throw invalidTable("Missing section: " + SECTION);
        }
        int sectionEnd = sectionStart + 1;
        while (sectionEnd < lines.size() && !lines.get(sectionEnd).startsWith("## ")) {
            sectionEnd++;
        }
        int header = sectionStart + 1;
        while (header < sectionEnd && !HEADER.matcher(lines.get(header)).matches()) {
            header++;
        }
        if (header + 1 >= sectionEnd || !SEPARATOR.matcher(lines.get(header + 1)).matches()) {
            throw invalidTable("Missing or malformed error-code table header/separator in section 5.");
        }
        Set<String> codes = new HashSet<>();
        for (int row = header + 2; row < sectionEnd && lines.get(row).startsWith("|"); row++) {
            var match = ROW.matcher(lines.get(row));
            if (!match.matches()) {
                throw invalidTable("Malformed error-code table row at line " + (row + 1));
            }
            codes.add(match.group(1));
        }
        if (codes.isEmpty()) {
            throw invalidTable("The error-code table in section 5 is empty.");
        }
        return Set.copyOf(codes);
    }

    /** Kiểm mọi hằng enum đã được ghi trong bảng; mã chỉ có ở tài liệu không phải vi phạm. */
    static void assertDocumented(Class<? extends Enum<?>> enumType, Set<String> documentedCodes) {
        List<String> missing = Arrays.stream(enumType.getEnumConstants())
                .map(Enum::name)
                .filter(code -> !documentedCodes.contains(code))
                .sorted()
                .toList();
        if (!missing.isEmpty()) {
            throw new AssertionError("Undocumented error codes in " + enumType.getName() + ": " + missing
                    + ". Add them to the error-code table in " + DOCUMENT + " section 5.");
        }
    }

    /** Gắn vị trí tài liệu vào lỗi parser để người sửa biết phải kiểm hợp đồng nào. */
    private static IllegalStateException invalidTable(String detail) {
        return new IllegalStateException(DOCUMENT + ": " + detail);
    }
}
