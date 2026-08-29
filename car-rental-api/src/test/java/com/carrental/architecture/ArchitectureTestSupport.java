package com.carrental.architecture;

import java.util.List;
import java.util.Objects;

final class ArchitectureTestSupport {

    private ArchitectureTestSupport() {
    }

    static void assertNoViolations(
            String ruleId,
            List<ArchitectureViolation> violations
    ) {
        List<ArchitectureViolation> checkedViolations =
                checkedViolations(ruleId, violations);

        if (checkedViolations.isEmpty()) {
            return;
        }

        throw new AssertionError(
                "[%s] Phát hiện %d vi phạm kiến trúc:%n%s"
                        .formatted(
                                ruleId,
                                checkedViolations.size(),
                                formatReport(checkedViolations)
                        )
        );
    }

    static void assertFixtureViolations(
            String ruleId,
            List<ArchitectureViolation> violations,
            int expectedCount,
            String... requiredFragments
    ) {
        if (expectedCount < 1) {
            throw new IllegalArgumentException(
                    "Test âm phải kỳ vọng ít nhất một vi phạm"
            );
        }

        Objects.requireNonNull(requiredFragments, "requiredFragments");

        List<ArchitectureViolation> checkedViolations =
                checkedViolations(ruleId, violations);

        if (checkedViolations.size() != expectedCount) {
            String actualReport = checkedViolations.isEmpty()
                    ? "<không có vi phạm>"
                    : formatReport(checkedViolations);

            throw new AssertionError(
                    """
                    [%s] Test âm không chứng minh được rule.
                    Kỳ vọng: %d vi phạm.
                    Thực tế: %d vi phạm.
                    Fixture có thể không còn vi phạm hoặc selector đang bỏ sót fixture.
                    %s
                    """.formatted(
                            ruleId,
                            expectedCount,
                            checkedViolations.size(),
                            actualReport
                    ).stripTrailing()
            );
        }

        String report = formatReport(checkedViolations);

        for (String requiredFragment : requiredFragments) {
            if (requiredFragment == null || requiredFragment.isBlank()) {
                throw new IllegalArgumentException(
                        "Nội dung bắt buộc trong report không được để trống"
                );
            }

            if (!report.contains(requiredFragment)) {
                throw new AssertionError(
                        """
                        [%s] Report của test âm thiếu nội dung bắt buộc: %s
                        Report thực tế:
                        %s
                        """.formatted(
                                ruleId,
                                requiredFragment,
                                report
                        ).stripTrailing()
                );
            }
        }
    }

    private static List<ArchitectureViolation> checkedViolations(
            String ruleId,
            List<ArchitectureViolation> violations
    ) {
        Objects.requireNonNull(ruleId, "ruleId");

        List<ArchitectureViolation> checked =
                List.copyOf(Objects.requireNonNull(violations, "violations"));

        List<String> unexpectedRuleIds = checked.stream()
                .map(ArchitectureViolation::ruleId)
                .filter(actualRuleId -> !ruleId.equals(actualRuleId))
                .distinct()
                .sorted()
                .toList();

        if (!unexpectedRuleIds.isEmpty()) {
            throw new AssertionError(
                    "[%s] Detector trả về vi phạm của rule khác: %s"
                            .formatted(ruleId, unexpectedRuleIds)
            );
        }

        return checked;
    }

    private static String formatReport(
            List<ArchitectureViolation> violations
    ) {
        return violations.stream()
                .sorted(ArchitectureViolation.ORDER)
                .map(ArchitectureViolation::format)
                .reduce((left, right) -> left + System.lineSeparator() + right)
                .orElse("<không có vi phạm>");
    }
}