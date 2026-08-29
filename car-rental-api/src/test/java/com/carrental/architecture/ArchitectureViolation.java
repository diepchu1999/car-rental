package com.carrental.architecture;

import java.util.Comparator;

record ArchitectureViolation(
        String ruleId,
        String location,
        String description,
        String remedy
) {

    static final Comparator<ArchitectureViolation> ORDER =
            Comparator.comparing(ArchitectureViolation::ruleId)
                    .thenComparing(ArchitectureViolation::location)
                    .thenComparing(ArchitectureViolation::description);

    ArchitectureViolation {
        ruleId = requireText(ruleId, "ruleId");
        location = requireText(location, "location");
        description = requireText(description, "description");
        remedy = requireText(remedy, "remedy");
    }

    String format() {
        return """
                [%s] %s
                  Vi phạm: %s
                  Cách sửa: %s
                """.formatted(ruleId, location, description, remedy).stripTrailing();
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " không được để trống");
        }
        return value;
    }
}