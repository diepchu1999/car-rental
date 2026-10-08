package com.carrental.architecture;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * R10: chặn search biết sở hữu qua VehicleRef hoặc hợp đồng vehicle.api (BR-112).
 * Đọc bytecode bằng ArchUnit, không nạp lớp bằng reflection hay chạy initializer.
 * Record được kiểm qua backing field và accessor do compiler tạo.
 */
final class VehicleSearchContractRule {
    /** Lớp chỉ cung cấp phép kiểm, không có trạng thái dùng chung giữa các lần đánh giá. */
    private VehicleSearchContractRule() {
    }

    /**
     * Kiểm đúng module search của root đã cho, không khớp nhầm package có chữ search ở giữa.
     * Mỗi cặp lớp tiêu thụ/hợp đồng chỉ báo một đường rò đầu tiên để record không báo trùng
     * field/accessor. Root tách biệt giúp fixture không lọt vào phạm vi code thật.
     */
    static List<ArchitectureViolation> evaluate(Iterable<JavaClass> classes, String root) {
        List<ArchitectureViolation> violations = new ArrayList<>();
        for (JavaClass origin : classes) {
            if (!inPackage(origin, root + ".search")) {
                continue;
            }
            Set<String> inspectedContracts = new HashSet<>();
            var dependencies = origin.getDirectDependenciesFromSelf().stream()
                    .sorted(Comparator.comparing(Dependency::getDescription)).toList();
            for (Dependency dependency : dependencies) {
                JavaClass target = baseType(dependency.getTargetClass());
                if (!inPackage(target, root + ".vehicle.api")
                        || !inspectedContracts.add(target.getName())) {
                    continue;
                }
                findLeak(target, root).ifPresent(path -> violations.add(new ArchitectureViolation(
                        "R10",
                        origin.getName() + " -> " + dependency.getDescription(),
                        "Search receives ownership information through " + path,
                        "Use VehicleSearchView with collateralFree and no OwnershipType or VehicleRef; "
                                + "see module-architecture.md sections 5/8 and BR-112.")));
            }
        }
        return violations.stream().sorted(ArchitectureViolation.ORDER).toList();
    }

    /** Kiểm một hợp đồng độc lập, kể cả trước khi module search có lớp sử dụng thật. */
    static Optional<String> findLeak(JavaClass contract, String root) {
        return findLeak(contract, root, new HashSet<>());
    }

    /**
     * Duyệt chữ ký field và kiểu trả về, gồm thành viên kế thừa; không duyệt thân method.
     * Chỉ đi tiếp vào kiểu vehicle.api, không quét nội bộ JDK hoặc module khác.
     * Tập visited chặn vòng tham chiếu giữa các view, không giới hạn độ sâu tùy tiện.
     */
    private static Optional<String> findLeak(JavaClass contract, String root, Set<String> visited) {
        JavaClass type = baseType(contract);
        if (type.getName().equals(root + ".vehicle.domain.OwnershipType")
                || type.getName().equals(root + ".vehicle.api.VehicleRef")) {
            return Optional.of(type.getName());
        }
        if (!inPackage(type, root + ".vehicle.api") || !visited.add(type.getName())) {
            return Optional.empty();
        }
        for (JavaField field : type.getAllFields().stream()
                .sorted(Comparator.comparing(JavaField::getFullName)).toList()) {
            Optional<String> leak = findLeakInType(field.getType(), root, visited);
            if (leak.isPresent()) {
                return Optional.of(field.getFullName() + " -> " + leak.orElseThrow());
            }
        }
        for (JavaMethod method : type.getAllMethods().stream()
                .sorted(Comparator.comparing(JavaMethod::getFullName)).toList()) {
            Optional<String> leak = findLeakInType(method.getReturnType(), root, visited);
            if (leak.isPresent()) {
                return Optional.of(method.getFullName() + " return -> " + leak.orElseThrow());
            }
        }
        return Optional.empty();
    }

    /** Kiểm cả tham số generic và mảng, không chỉ kiểu đã xóa generic như List hoặc Optional. */
    private static Optional<String> findLeakInType(JavaType signature, String root, Set<String> visited) {
        for (JavaClass type : signature.getAllInvolvedRawTypes().stream()
                .sorted(Comparator.comparing(JavaClass::getName)).toList()) {
            Optional<String> leak = findLeak(type, root, visited);
            if (leak.isPresent()) {
                return leak;
            }
        }
        return Optional.empty();
    }

    /** Chuẩn hóa mảng về kiểu phần tử cuối để OwnershipType[] không vượt qua rule. */
    private static JavaClass baseType(JavaClass type) {
        return type.isArray() ? type.getBaseComponentType() : type;
    }

    /** So khớp biên package tường minh, không coi vehicle.apiculture là vehicle.api. */
    private static boolean inPackage(JavaClass type, String packageName) {
        return type.getPackageName().equals(packageName)
                || type.getPackageName().startsWith(packageName + ".");
    }
}
