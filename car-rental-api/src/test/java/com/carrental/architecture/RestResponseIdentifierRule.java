package com.carrental.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaModifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** R12 kiểm thành phần record response bằng bytecode, không nạp lớp hoặc chạy initializer. */
final class RestResponseIdentifierRule {
    private static final Set<String> NUMERIC_PRIMITIVES = Set.of("byte", "short", "int", "long", "float", "double");

    /** Rule không giữ trạng thái và không phụ thuộc tên module nghiệp vụ cụ thể. */
    private RestResponseIdentifierRule() {
    }

    /**
     * Record chỉ có instance field tương ứng thành phần record; bỏ static field để không bắt hằng nội bộ.
     * Chỉ cấm kiểu số trực tiếp với tên id hoặc đuôi Id, không cấm số chỗ, tọa độ hay chuỗi mã.
     * Root riêng cho fixture bảo đảm test âm không lọt vào phạm vi quét production.
     */
    static List<ArchitectureViolation> evaluate(Iterable<JavaClass> classes, String root) {
        List<ArchitectureViolation> violations = new ArrayList<>();
        Pattern restPackage = Pattern.compile(Pattern.quote(root) + "\\.[^.]+\\.adapter\\.in\\.rest(?:\\..*)?");
        for (JavaClass type : classes) {
            if (!type.isRecord() || !restPackage.matcher(type.getPackageName()).matches() || !isResponse(type)) {
                continue;
            }
            for (var field : type.getFields()) {
                if (field.getModifiers().contains(JavaModifier.STATIC)) {
                    continue;
                }
                String name = field.getName();
                JavaClass fieldType = field.getRawType();
                if ((name.equals("id") || name.endsWith("Id"))
                        && (NUMERIC_PRIMITIVES.contains(fieldType.getName()) || fieldType.isAssignableTo(Number.class))) {
                    violations.add(new ArchitectureViolation("R12", field.getFullName(),
                            "REST response record exposes a numeric identifier: " + fieldType.getName() + " " + name,
                            "Return a business code and resolve cross-module references through the owning module API; "
                                    + "see database-guideline.md section 2 and ADR-0008."));
                }
            }
        }
        return violations.stream().sorted(ArchitectureViolation.ORDER).toList();
    }

    /**
     * Nhận diện package response chuẩn hoặc hậu tố Response khi đặt trực tiếp trong rest.
     * Record lồng trong response cũng thuộc response dù tên lớp con không có hậu tố này.
     * Request record ở package request không thuộc rule nếu không mang tên response.
     */
    private static boolean isResponse(JavaClass type) {
        if (("." + type.getPackageName() + ".").contains(".response.")) {
            return true;
        }
        for (JavaClass current = type; current != null; current = current.getEnclosingClass().orElse(null)) {
            if (current.getSimpleName().endsWith("Response")) {
                return true;
            }
        }
        return false;
    }
}
