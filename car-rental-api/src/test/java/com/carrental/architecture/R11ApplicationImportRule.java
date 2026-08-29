package com.carrental.architecture;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.util.Trees;

import javax.tools.Diagnostic;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Kiểm các import bị cấm trong application theo R11 và ADR-0004.
 *
 * <p>Chỉ đọc cấu trúc import từ AST, không tìm chuỗi trong source
 * và không cần phân giải các kiểu được import.
 *
 * <p>Không cấm toàn bộ Spring: Service, Component, Qualifier
 * và Transactional vẫn được phép.
 */
final class R11ApplicationImportRule {

    private static final String RULE_ID = "R11";

    private static final Set<String> FORBIDDEN_PACKAGE_PREFIXES = Set.of(
            "org.springframework.web",
            "org.springframework.http",
            "jakarta.servlet",
            "org.springframework.jdbc",
            "java.sql",
            "javax.sql",
            "com.fasterxml.jackson",
            "tools.jackson"
    );

    private static final Set<String> FORBIDDEN_TYPES = Set.of(
            "org.springframework.context.annotation.Configuration",
            "org.springframework.context.annotation.Bean"
    );

    private static final String CONFIGURATION_PACKAGE_WILDCARD =
            "org.springframework.context.annotation.*";

    private static final String REMEDY =
            "Move HTTP, persistence and serialization dependencies to adapters; "
                    + "move bean configuration to shared/config or module/config. "
                    + "Service, Component, Qualifier and Transactional are allowed. "
                    + "See module-architecture.md section 8 and ADR-0004.";

    /**
     * Ngăn tạo đối tượng vì lớp chỉ cung cấp thao tác kiểm tra tĩnh.
     */
    private R11ApplicationImportRule() {
    }

    /**
     * Kiểm các import trong một file thuộc package application.
     *
     * <p>Mỗi câu import bị cấm tạo một vi phạm riêng với vị trí dòng.
     * Package ngoài application không thuộc phạm vi của rule này.
     *
     * @param trees công cụ lấy vị trí của cây cú pháp
     * @param unit cây cú pháp của file Java
     * @param projectRoot thư mục gốc dùng để hiển thị đường dẫn tương đối
     * @return danh sách vi phạm bất biến, có thể rỗng
     */
    static List<ArchitectureViolation> inspect(
            Trees trees,
            CompilationUnitTree unit,
            Path projectRoot
    ) {
        if (unit.getPackageName() == null) {
            return List.of();
        }

        String packageName = unit.getPackageName().toString();

        if (!("." + packageName + ".").contains(".application.")) {
            return List.of();
        }

        Path sourcePath = Path.of(unit.getSourceFile().toUri())
                .toAbsolutePath()
                .normalize();

        String displayPath = sourcePath.startsWith(projectRoot)
                ? projectRoot.relativize(sourcePath).toString()
                : sourcePath.toString();

        List<ArchitectureViolation> violations = new ArrayList<>();

        for (ImportTree imported : unit.getImports()) {
            String importedName =
                    imported.getQualifiedIdentifier().toString();

            if (!isForbiddenImport(importedName)) {
                continue;
            }

            long position = trees.getSourcePositions()
                    .getStartPosition(unit, imported);

            String line = position == Diagnostic.NOPOS
                    ? "?"
                    : Long.toString(
                    unit.getLineMap().getLineNumber(position)
            );

            violations.add(new ArchitectureViolation(
                    RULE_ID,
                    displayPath + ":" + line,
                    "Application imports a forbidden dependency: "
                            + importedName + ".",
                    REMEDY
            ));
        }

        return List.copyOf(violations);
    }

    /**
     * Nhận diện import thuộc namespace hoặc kiểu bị cấm.
     *
     * <p>So khớp theo ranh giới dấu chấm, tránh bắt nhầm package
     * hoặc kiểu chỉ có tên bắt đầu giống nhau.
     *
     * <p>Import wildcard của context.annotation cũng bị từ chối
     * vì nó đưa cả Configuration và Bean vào phạm vi tra cứu.
     * Bên gọi cần dùng import tường minh cho kiểu được phép.
     *
     * @param importedName tên đầy đủ của import, kể cả wildcard hoặc static
     * @return true nếu import vi phạm R11
     */
    private static boolean isForbiddenImport(String importedName) {
        if (CONFIGURATION_PACKAGE_WILDCARD.equals(importedName)) {
            return true;
        }

        return FORBIDDEN_PACKAGE_PREFIXES.stream().anyMatch(prefix ->
                importedName.equals(prefix)
                        || importedName.startsWith(prefix + ".")
        ) || FORBIDDEN_TYPES.stream().anyMatch(type ->
                importedName.equals(type)
                        || importedName.startsWith(type + ".")
        );
    }
}