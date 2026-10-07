package com.carrental.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static com.carrental.architecture.ArchitectureTestSupport.assertFixtureViolations;
import static com.carrental.architecture.ArchitectureTestSupport.assertNoViolations;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;


/** Khóa các ranh giới kiến trúc bằng code thật và fixture âm/dương độc lập. */
class ArchitectureRulesTest {

    private static final String R1 = "R1";
    private static final String R2 = "R2";
    private static final String R3 = "R3";
    private static final String R4 = "R4";
    private static final String R5 = "R5";
    private static final String R6 = "R6";
    private static final String R7 = "R7";
    private static final String R8 = "R8";
    private static final String R9 = "R9";
    private static final String R10 = "R10";
    private static final String R11 = "R11";

    private static final String PRODUCTION_ROOT_PACKAGE = "com.carrental";
    private static final String R3_FIXTURE_ROOT_PACKAGE =
            "com.carrental.architecture.fixtures.r3";
    private static final String R10_FIXTURE_ROOT =
            "com.carrental.architecture.fixtures.r10";
    private static final String R10_CONTRACT_FIXTURE_ROOT =
            "com.carrental.architecture.fixtures.r10contracts";

    private static final Path BACKEND_ROOT = locateBackendRoot();

    private static final Path PRODUCTION_SOURCE_ROOT =
            BACKEND_ROOT.resolve("src/main/java");

    private static final Path R11_FORBIDDEN_FIXTURE_SOURCE_ROOT =
            BACKEND_ROOT.resolve(
                    "src/test/resources/architecture-fixtures/r11/forbidden/java"
            );

    private static final Path R11_ALLOWED_FIXTURE_SOURCE_ROOT =
            BACKEND_ROOT.resolve(
                    "src/test/resources/architecture-fixtures/r11/allowed/java"
            );

    private static final Path R5_FIXTURE_SOURCE_ROOT =
            BACKEND_ROOT.resolve(
                    "src/test/resources/architecture-fixtures/r5/java"
            );

    private static final Path R6_FIXTURE_SOURCE_ROOT =
            BACKEND_ROOT.resolve(
                    "src/test/java/com/carrental/architecture/fixtures/r6"
            );

    private static final Path R6_ALLOWED_FIXTURE_SOURCE_ROOT =
            BACKEND_ROOT.resolve(
                    "src/test/java/com/carrental/architecture/fixtures/r6allowed"
            );

    private static final Path R6_SCOPE_FIXTURE_SOURCE_ROOT =
            BACKEND_ROOT.resolve(
                    "src/test/java/com/carrental/architecture/fixtures/r6scope"
            );

    private static final Path R9_FIXTURE_SOURCE_ROOT =
            BACKEND_ROOT.resolve(
                    "src/test/java/com/carrental/architecture/fixtures/r9"
            );

    private static final Path PRODUCTION_RESOURCE_ROOT =
            BACKEND_ROOT.resolve("src/main/resources");

    private static final Path R7_FIXTURE_RESOURCE_ROOT =
            BACKEND_ROOT.resolve(
                    "src/test/resources/architecture-fixtures/r7"
            );

    private static final Path R8_FIXTURE_RESOURCE_ROOT =
            BACKEND_ROOT.resolve(
                    "src/test/resources/architecture-fixtures/r8"
            );

    private static final JavaSourceArchitectureScanner.SourceScanResult
            PRODUCTION_SOURCE_SCAN =
            JavaSourceArchitectureScanner.scan(
                    BACKEND_ROOT,
                    PRODUCTION_SOURCE_ROOT
            );

    private static final List<ArchitectureViolation>
            R11_FORBIDDEN_FIXTURE_VIOLATIONS =
            JavaSourceArchitectureScanner.scanForbiddenApplicationImports(
                    BACKEND_ROOT,
                    R11_FORBIDDEN_FIXTURE_SOURCE_ROOT
            );

    private static final List<ArchitectureViolation>
            R11_ALLOWED_FIXTURE_VIOLATIONS =
            JavaSourceArchitectureScanner.scanForbiddenApplicationImports(
                    BACKEND_ROOT,
                    R11_ALLOWED_FIXTURE_SOURCE_ROOT
            );

    private static final List<ArchitectureViolation>
            R5_FIXTURE_VIOLATIONS =
            JavaSourceArchitectureScanner.scanForbiddenJpaImports(
                    BACKEND_ROOT,
                    R5_FIXTURE_SOURCE_ROOT
            );

    private static final JavaSourceArchitectureScanner.SourceScanResult
            R6_FIXTURE_SOURCE_SCAN =
            JavaSourceArchitectureScanner.scan(
                    BACKEND_ROOT,
                    R6_FIXTURE_SOURCE_ROOT
            );

    private static final JavaSourceArchitectureScanner.SourceScanResult
            R6_ALLOWED_FIXTURE_SOURCE_SCAN =
            JavaSourceArchitectureScanner.scan(
                    BACKEND_ROOT,
                    R6_ALLOWED_FIXTURE_SOURCE_ROOT
            );

    private static final JavaSourceArchitectureScanner.SourceScanResult
            R6_SCOPE_FIXTURE_SOURCE_SCAN =
            JavaSourceArchitectureScanner.scan(
                    BACKEND_ROOT,
                    R6_SCOPE_FIXTURE_SOURCE_ROOT
            );

    private static final JavaSourceArchitectureScanner.SourceScanResult
            R9_FIXTURE_SOURCE_SCAN =
            JavaSourceArchitectureScanner.scan(
                    BACKEND_ROOT,
                    R9_FIXTURE_SOURCE_ROOT
            );

    private static final SqlArchitectureScanner.SqlScanResult
            PRODUCTION_SQL_SCAN =
            SqlArchitectureScanner.scan(
                    BACKEND_ROOT,
                    PRODUCTION_RESOURCE_ROOT
            );

    private static final SqlArchitectureScanner.SqlScanResult
            R7_FIXTURE_SQL_SCAN =
            SqlArchitectureScanner.scan(
                    BACKEND_ROOT,
                    R7_FIXTURE_RESOURCE_ROOT
            );

    private static final SqlArchitectureScanner.SqlScanResult
            R8_FIXTURE_SQL_SCAN =
            SqlArchitectureScanner.scan(
                    BACKEND_ROOT,
                    R8_FIXTURE_RESOURCE_ROOT
            );

    private static final String R1_DESCRIPTION =
            "Lớp application phụ thuộc trực tiếp vào lớp adapter.";

    private static final String R1_REMEDY =
            "Định nghĩa port trong application/port/out và để adapter hiện thực port; "
                    + "xem module-architecture.md §1, §4 và ADR-0004.";

    private static final String R2_DESCRIPTION =
            "Lớp domain phụ thuộc application, adapter hoặc framework bị cấm.";

    private static final String R2_REMEDY =
            "Giữ domain thuần Java; chuyển điều phối sang application và chuyển "
                    + "Spring, JDBC, web, Jackson sang adapter; xem "
                    + "module-architecture.md §1, §2 và ADR-0004.";

    private static final String R3_DESCRIPTION =
            "Module phụ thuộc vào package nội bộ của module khác.";

    private static final String R3_REMEDY =
            "Chỉ giao tiếp cross-module qua package api của module cung cấp; "
                    + "xem module-architecture.md §5 và ADR-0008.";

    private static final String R4_DESCRIPTION =
            "Write port nhận read view làm tham số.";

    private static final String R4_REMEDY =
            "Thay read view bằng command, domain value hoặc ID tối thiểu; "
                    + "read view chỉ dành cho luồng đọc; xem "
                    + "module-architecture.md §3 và §8.";


    private static final JavaClasses PRODUCTION_CLASSES =
            new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .importPackages("com.carrental");

    private static final JavaClasses R1_FIXTURE_CLASSES =
            new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.ONLY_INCLUDE_TESTS)
                    .importPackages(
                            "com.carrental.architecture.fixtures.r1"
                    );

    private static final JavaClasses R2_FIXTURE_CLASSES =
            new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.ONLY_INCLUDE_TESTS)
                    .importPackages(
                            "com.carrental.architecture.fixtures.r2"
                    );

    private static final JavaClasses R3_FIXTURE_CLASSES =
            new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.ONLY_INCLUDE_TESTS)
                    .importPackages(R3_FIXTURE_ROOT_PACKAGE);

    private static final JavaClasses R4_FIXTURE_CLASSES =
            new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.ONLY_INCLUDE_TESTS)
                    .importPackages(
                            "com.carrental.architecture.fixtures.r4"
                    );

    private static final ArchRule R4_RULE = r4Rule();

    private static final JavaClasses R10_FIXTURE_CLASSES =
            new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.ONLY_INCLUDE_TESTS)
                    .importPackages(
                            "com.carrental.architecture.fixtures.r10"
                    );

    private static final JavaClasses R10_CONTRACT_FIXTURE_CLASSES =
            new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.ONLY_INCLUDE_TESTS)
                    .importPackages(R10_CONTRACT_FIXTURE_ROOT);


    private static final ArchRule R1_RULE =
            noClasses()
                    .that()
                    .resideInAPackage("..application..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("..adapter..")
                    .because(
                            "application chỉ được phụ thuộc domain và các port, "
                                    + "không được phụ thuộc adapter"
                    )
                    .allowEmptyShould(true);

    private static final ArchRule R2_RULE =
            noClasses()
                    .that()
                    .resideInAPackage("..domain..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "com.carrental..application..",
                            "com.carrental..adapter..",
                            "org.springframework..",
                            "java.sql..",
                            "javax.sql..",
                            "jakarta.servlet..",
                            "javax.servlet..",
                            "jakarta.ws.rs..",
                            "javax.ws.rs..",
                            "jakarta.websocket..",
                            "javax.websocket..",
                            "java.net.http..",
                            "com.fasterxml.jackson..",
                            "tools.jackson.."
                    )
                    .because(
                            "domain phải thuần Java và không phụ thuộc các tầng bên ngoài"
                    )
                    .allowEmptyShould(true);

    @Test
    void r1RealSourceApplicationDoesNotDependOnAdapter() {
        assertNoViolations(
                R1,
                evaluateR1(PRODUCTION_CLASSES)
        );
    }

    @Test
    void r1NegativeFixtureDetectsApplicationDependingOnAdapter() {
        assertFixtureViolations(
                R1,
                evaluateR1(R1_FIXTURE_CLASSES),
                1,
                "R1ApplicationViolation",
                "R1Adapter",
                "application",
                "adapter",
                "ADR-0004"
        );
    }

    @Test
    void r2RealSourceDomainIsIndependentFromOuterLayersAndFrameworks() {
        assertNoViolations(
                R2,
                evaluateR2(PRODUCTION_CLASSES)
        );
    }

    @Test
    void r2NegativeFixtureDetectsForbiddenDomainDependencies() {
        assertFixtureViolations(
                R2,
                evaluateR2(R2_FIXTURE_CLASSES),
                3,
                "R2DomainViolation",
                "R2ApplicationType",
                "R2AdapterType",
                "JdbcTemplate",
                "domain",
                "ADR-0004"
        );
    }

    @Test
    void r3RealSourceCrossModuleDependenciesUseApiPackages() {
        assertNoViolations(
                R3,
                evaluateR3(PRODUCTION_CLASSES, PRODUCTION_ROOT_PACKAGE)
        );
    }

    @Test
    void r3NegativeFixtureDetectsDependencyOnAnotherModulesInternals() {
        assertFixtureViolations(
                R3,
                evaluateR3(R3_FIXTURE_CLASSES, R3_FIXTURE_ROOT_PACKAGE),
                1,
                "R3ConsumerViolation",
                "R3ProviderInternalType",
                "consumer",
                "provider",
                "api",
                "ADR-0008"
        );
    }

    @Test
    void r4RealSourceWritePortsDoNotAcceptReadViews() {
        assertNoViolations(
                R4,
                evaluateR4(PRODUCTION_CLASSES)
        );
    }

    @Test
    void r4NegativeFixtureDetectsNestedGenericReadViews() {
        assertFixtureViolations(
                R4,
                evaluateR4(R4_FIXTURE_CLASSES),
                3,
                "WriteBookingPort",
                "save",
                "BookingDetail",
                "BookingListItem",
                "BookingSummary",
                "read view",
                "module-architecture.md §3"
        );
    }

    @Test
    void r5RealSourcePersistenceAdaptersDoNotImportJpa() {
        assertNoViolations(
                R5,
                PRODUCTION_SOURCE_SCAN.r5Violations()
        );
    }

    @Test
    void r5NegativeFixtureDetectsAllForbiddenJpaImports() {
        assertFixtureViolations(
                R5,
                R5_FIXTURE_VIOLATIONS,
                3,
                "R5JpaPersistenceViolation.java:3",
                "jakarta.persistence.Entity",
                "R5JpaPersistenceViolation.java:4",
                "javax.persistence.Table",
                "R5JpaPersistenceViolation.java:5",
                "org.springframework.data.jpa.repository.JpaRepository",
                "Persistence adapter",
                "NamedParameterJdbcTemplate",
                "ADR-0003"
        );
    }

    @Test
    void r6RealSourceBranchesOnPolicyAxesOnlyInsidePolicyResolvers() {
        assertNoViolations(
                R6,
                PRODUCTION_SOURCE_SCAN.r6Violations()
        );
    }

    @Test
    void r6NegativeFixtureDetectsPolicyBranchingOutsideResolver() {
        assertFixtureViolations(
                R6,
                R6_FIXTURE_SOURCE_SCAN.r6Violations(),
                2,
                "R6PolicyBranchViolation.java:22",
                "R6PolicyBranchViolation.java:30",
                "OwnershipType",
                "RentalType",
                "==/!=",
                "switch",
                "*PolicyResolver",
                "ADR-0004"
        );
    }

    @Test
    void r6AllowedFixturePermitsBothPolicyAxesInsidePolicyResolver() {
        assertNoViolations(
                R6,
                R6_ALLOWED_FIXTURE_SOURCE_SCAN.r6Violations()
        );
    }

    @Test
    void r6NestedAndAnonymousClassesDoNotInheritResolverExemption() {
        assertFixtureViolations(
                R6,
                R6_SCOPE_FIXTURE_SOURCE_SCAN.r6Violations(),
                2,
                "R6NearestClassPolicyResolver.java:20",
                "R6NearestClassPolicyResolver.java:30",
                "OwnershipType",
                "RentalType",
                "==/!=",
                "switch",
                "*PolicyResolver",
                "ADR-0004"
        );
    }

    @Test
    void r7RealSqlAllowsOnlyAvailabilityToWriteReservation() {
        assertNoViolations(
                R7,
                PRODUCTION_SQL_SCAN.r7Violations()
        );
    }

    @Test
    void r7NegativeFixtureDetectsReservationWriteOutsideAvailability() {
        assertFixtureViolations(
                R7,
                R7_FIXTURE_SQL_SCAN.r7Violations(),
                1,
                "r7_forbidden_reservation_write.sql:15",
                "booking",
                "UPDATE",
                "availability.reservation",
                "module availability",
                "ADR-0005"
        );
    }

    @Test
    void r8RealSqlDoesNotDestroyImmutableEvidence() {
        assertNoViolations(
                R8,
                PRODUCTION_SQL_SCAN.r8Violations()
        );
    }

    @Test
    void r8NegativeFixtureDetectsForbiddenEvidenceDestruction() {
        assertFixtureViolations(
                R8,
                R8_FIXTURE_SQL_SCAN.r8Violations(),
                3,
                "V999__legacy_cleanup.sql:4",
                "<unclassified>",
                "schema handover/compliance",
                "DROP TABLE",
                "r8_forbidden_handover_destroy.sql:16",
                "r8_forbidden_handover_destroy.sql:18",
                "handover",
                "DELETE",
                "TRUNCATE",
                "ADR-0015"
        );
    }

    @Test
    void r9RealSourceSettlementRelatedModulesUseBookingSnapshots() {
        assertNoViolations(
                R9,
                PRODUCTION_SOURCE_SCAN.r9Violations()
        );
    }

    @Test
    void r9NegativeFixtureDetectsConfigurationPortInBooking() {
        assertFixtureViolations(
                R9,
                R9_FIXTURE_SOURCE_SCAN.r9Violations(),
                4,
                "R9BookingViolation.java:7",
                "booking",
                "R9HandoverViolation.java:7",
                "handover",
                "R9PaymentViolation.java:7",
                "payment",
                "R9SettlementViolation.java:7",
                "settlement",
                "ConfigurationPort",
                "snapshot",
                "ADR-0014"
        );
    }

    /** Code thật không được nhận VehicleRef hoặc hợp đồng lộ OwnershipType. */
    @Test
    void r10RealSourceSearchDoesNotReceiveOwnership() {
        assertNoViolations(
                R10,
                evaluateR10(
                        PRODUCTION_CLASSES,
                        PRODUCTION_ROOT_PACKAGE
                )
        );
    }

    /** Giữ test âm cũ: tên VehicleRef vẫn bị cấm dù fixture chưa có OwnershipType. */
    @Test
    void r10NegativeFixtureDetectsSearchReferencingVehicleRef() {
        assertFixtureViolations(
                R10,
                evaluateR10(
                        R10_FIXTURE_CLASSES,
                        R10_FIXTURE_ROOT
                ),
                1,
                "R10SearchVehicleRefViolation",
                "forbiddenVehicleRef",
                "VehicleRef",
                "VehicleSearchView",
                "BR-112"
        );
    }

    /** Mỗi dạng rò có đúng một vi phạm, report phải chỉ rõ đường tới kiểu cấm. */
    @ParameterizedTest
    @CsvSource({
            "FieldLeak,FieldView.classification,OwnershipType",
            "RecordLeak,RecordView.classification,OwnershipType",
            "MethodLeak,MethodView.classification(),OwnershipType",
            "GenericLeak,GenericView.classifications(),OwnershipType",
            "ArrayLeak,ArrayView.classifications(),OwnershipType",
            "NestedLeak,NestedDirectory.list(),OwnershipType",
            "InheritedLeak,MethodView.classification(),OwnershipType",
            "RefLeak,RefDirectory.find(),VehicleRef"
    })
    void r10NegativeFixturesDetectOwnershipInContracts(String consumer, String member, String forbidden) {
        assertFixtureViolations(R10, evaluateR10Consumer(consumer), 1,
                consumer, member, forbidden, "VehicleSearchView", "BR-112");
    }

    /** Hợp đồng có collateralFree và generic sạch được phép; dùng test này cho phép thử đảo. */
    @Test
    void r10AllowedFixturePermitsOwnershipFreeContracts() {
        assertNoViolations(R10, evaluateR10Consumer("Allowed"));
    }

    /** Đồ thị kiểu có chu trình nhưng không có sở hữu không bị chặn hoặc duyệt vô hạn. */
    @Test
    void r10AllowedFixtureHandlesContractCycles() {
        assertNoViolations(R10, evaluateR10Consumer("CycleAllowed"));
    }

    /** R10 không cấm booking nhận loại sở hữu qua API để phân giải policy. */
    @Test
    void r10AllowedFixtureDoesNotRestrictBooking() {
        JavaClass consumer = requireImportedClass(R10_CONTRACT_FIXTURE_CLASSES,
                R10_CONTRACT_FIXTURE_ROOT + ".booking.application.BookingConsumer");
        assertNoViolations(R10, VehicleSearchContractRule.evaluate(List.of(consumer), R10_CONTRACT_FIXTURE_ROOT));
    }

    /** Kiểm hợp đồng thật ngay cả khi search chưa được hiện thực, tránh chỉ có test production rỗng. */
    @Test
    void r10ProductionVehicleSearchContractDoesNotExposeOwnership() {
        for (String type : List.of("VehicleSearchDirectory", "VehicleSearchView")) {
            JavaClass contract = requireImportedClass(PRODUCTION_CLASSES,
                    PRODUCTION_ROOT_PACKAGE + ".vehicle.api." + type);
            Optional<String> leak = VehicleSearchContractRule.findLeak(contract, PRODUCTION_ROOT_PACKAGE);
            org.junit.jupiter.api.Assertions.assertTrue(leak.isEmpty(),
                    () -> "R10: production search contract leaks ownership: " + leak.orElse(""));
        }
    }

    /** Chọn đúng consumer đã import đầy đủ dependency; thiếu fixture phải lỗi thay vì test xanh rỗng. */
    private static List<ArchitectureViolation> evaluateR10Consumer(String name) {
        JavaClass consumer = requireImportedClass(R10_CONTRACT_FIXTURE_CLASSES,
                R10_CONTRACT_FIXTURE_ROOT + ".search.application.Consumers$" + name);
        return VehicleSearchContractRule.evaluate(List.of(consumer), R10_CONTRACT_FIXTURE_ROOT);
    }

    /** Không cho selector im lặng bỏ qua lớp bị đổi tên hoặc thiếu khỏi tập import. */
    private static JavaClass requireImportedClass(JavaClasses classes, String name) {
        for (JavaClass candidate : classes) {
            if (candidate.getName().equals(name)) {
                return candidate;
            }
        }
        throw new AssertionError("Required architecture class was not imported: " + name);
    }

    private static List<ArchitectureViolation> evaluateR1(
            JavaClasses classes
    ) {
        return R1_RULE.evaluate(classes)
                .getFailureReport()
                .getDetails()
                .stream()
                .map(detail -> new ArchitectureViolation(
                        R1,
                        detail,
                        R1_DESCRIPTION,
                        R1_REMEDY
                ))
                .toList();
    }

    /**
     * Chứng minh application trong source thật không import
     * công nghệ bị R11 cấm theo ADR-0004.
     */
    @Test
    void r11RealSourceApplicationAvoidsForbiddenImports() {
        assertNoViolations(
                R11,
                PRODUCTION_SOURCE_SCAN.r11Violations()
        );
    }

    /**
     * Chứng minh scanner phát hiện đủ các nhóm import bị cấm.
     *
     * <p>Kiểm cả số lượng, tên file, dependency và hướng khắc phục.
     * Wildcard và static import không được bỏ sót.
     */
    @Test
    void r11NegativeFixtureDetectsForbiddenApplicationImports() {
        assertFixtureViolations(
                R11,
                R11_FORBIDDEN_FIXTURE_VIOLATIONS,
                12,
                "R11ForbiddenApplicationImports.java:",
                "org.springframework.web.bind.annotation.RestController",
                "org.springframework.http.ResponseEntity",
                "jakarta.servlet.ServletRequest",
                "org.springframework.jdbc.core.JdbcTemplate",
                "java.sql.Connection",
                "javax.sql.DataSource",
                "com.fasterxml.jackson.databind.JsonNode",
                "tools.jackson.databind.ObjectMapper",
                "org.springframework.context.annotation.Configuration",
                "org.springframework.context.annotation.Bean",
                "org.springframework.context.annotation.*",
                "org.springframework.http.HttpStatus.OK",
                "Application imports a forbidden dependency",
                "shared/config",
                "ADR-0004"
        );
    }

    /**
     * Chứng minh R11 vẫn cho phép tiêm phụ thuộc và transaction.
     *
     * <p>Fixture sử dụng Service, Component, Qualifier và Transactional.
     * Nội dung giống import trong comment hoặc chuỗi không phải vi phạm.
     */
    @Test
    void r11AllowedFixturePermitsDependencyInjectionAndTransactions() {
        assertNoViolations(
                R11,
                R11_ALLOWED_FIXTURE_VIOLATIONS
        );
    }

    private static List<ArchitectureViolation> evaluateR2(
            JavaClasses classes
    ) {
        return R2_RULE.evaluate(classes)
                .getFailureReport()
                .getDetails()
                .stream()
                .map(detail -> new ArchitectureViolation(
                        R2,
                        detail,
                        R2_DESCRIPTION,
                        R2_REMEDY
                ))
                .toList();
    }

    private static List<ArchitectureViolation> evaluateR3(
            JavaClasses classes,
            String rootPackage
    ) {
        return r3Rule(rootPackage)
                .evaluate(classes)
                .getFailureReport()
                .getDetails()
                .stream()
                .map(detail -> new ArchitectureViolation(
                        R3,
                        detail,
                        R3_DESCRIPTION,
                        R3_REMEDY
                ))
                .toList();
    }

    private static List<ArchitectureViolation> evaluateR4(
            JavaClasses classes
    ) {
        return R4_RULE.evaluate(classes)
                .getFailureReport()
                .getDetails()
                .stream()
                .map(detail -> new ArchitectureViolation(
                        R4,
                        detail,
                        R4_DESCRIPTION,
                        R4_REMEDY
                ))
                .toList();
    }

    private static ArchRule r4Rule() {
        DescribedPredicate<JavaClass> writePorts =
                new DescribedPredicate<>(
                        "Write<X>Port classes in application.port.out"
                ) {
                    @Override
                    public boolean test(JavaClass javaClass) {
                        String packageName = javaClass.getPackageName();

                        boolean isInWritePortPackage =
                                packageName.endsWith(
                                        ".application.port.out"
                                )
                                        || packageName.contains(
                                        ".application.port.out."
                                );

                        return isInWritePortPackage
                                && javaClass.getSimpleName()
                                .matches("^Write.+Port$");
                    }
                };

        return classes()
                .that(writePorts)
                .should(new ArchCondition<JavaClass>(
                        "not accept Detail, ListItem or Summary read views"
                ) {
                    @Override
                    public void check(
                            JavaClass writePort,
                            ConditionEvents events
                    ) {
                        for (JavaMethod method : writePort.getAllMethods()) {
                            for (JavaType parameterType
                                    : method.getParameterTypes()) {
                                parameterType.getAllInvolvedRawTypes()
                                        .stream()
                                        .map(type -> type.isArray()
                                                ? type.getBaseComponentType()
                                                : type)
                                        .filter(
                                                ArchitectureRulesTest
                                                        ::isReadViewType
                                        )
                                        .distinct()
                                        .forEach(readViewType -> {
                                            String message =
                                                    "%s nhận read view %s "
                                                            .formatted(
                                                                    method.getFullName(),
                                                                    readViewType.getName()
                                                            )
                                                            + "qua tham số "
                                                            + parameterType.getName()
                                                            + " tại "
                                                            + method.getSourceCodeLocation();

                                            events.add(
                                                    SimpleConditionEvent.violated(
                                                            method,
                                                            message
                                                    )
                                            );
                                        });
                            }
                        }
                    }
                })
                .because(
                        "write input phải là command hoặc domain value, "
                                + "không phải read model"
                )
                .allowEmptyShould(true);
    }

    private static boolean isReadViewType(JavaClass javaClass) {
        String simpleName = javaClass.getSimpleName();

        return simpleName.endsWith("Detail")
                || simpleName.endsWith("ListItem")
                || simpleName.endsWith("Summary");
    }


    /** Dùng cùng detector cho production và fixture, chỉ khác namespace gốc. */
    private static List<ArchitectureViolation> evaluateR10(JavaClasses classes, String rootPackage) {
        return VehicleSearchContractRule.evaluate(classes, rootPackage);
    }

    private static ArchRule r3Rule(String rootPackage) {
        return classes()
                .that()
                .resideInAPackage(rootPackage + "..")
                .should(new ArchCondition<>(
                        "depend on other modules only through their api package"
                ) {
                    @Override
                    public void check(
                            JavaClass originClass,
                            ConditionEvents events
                    ) {
                        Optional<String> originModule = moduleName(
                                originClass.getPackageName(),
                                rootPackage
                        );

                        if (originModule.isEmpty()) {
                            return;
                        }

                        for (Dependency dependency
                                : originClass.getDirectDependenciesFromSelf()) {
                            JavaClass targetClass = dependency.getTargetClass();
                            Optional<String> targetModule = moduleName(
                                    targetClass.getPackageName(),
                                    rootPackage
                            );

                            if (targetModule.isEmpty()
                                    || originModule.get().equals(targetModule.get())
                                    || targetModule.get().equals("shared")
                                    || isApiPackage(
                                            targetClass.getPackageName(),
                                            rootPackage,
                                            targetModule.get()
                                    )) {
                                continue;
                            }

                            String message =
                                    "%s; module '%s' phụ thuộc nội bộ module '%s' "
                                            .formatted(
                                                    dependency.getDescription(),
                                                    originModule.get(),
                                                    targetModule.get()
                                            )
                                            + "thay vì đi qua package api.";

                            events.add(
                                    SimpleConditionEvent.violated(
                                            dependency,
                                            message
                                    )
                            );
                        }
                    }
                })
                .because(
                        "module chỉ được giao tiếp với module khác qua public api"
                )
                .allowEmptyShould(true);
    }

    private static Optional<String> moduleName(
            String packageName,
            String rootPackage
    ) {
        String modulePrefix = rootPackage + ".";

        if (!packageName.startsWith(modulePrefix)) {
            return Optional.empty();
        }

        String remainder = packageName.substring(modulePrefix.length());
        int separatorIndex = remainder.indexOf('.');
        String moduleName = separatorIndex < 0
                ? remainder
                : remainder.substring(0, separatorIndex);

        return moduleName.isBlank()
                ? Optional.empty()
                : Optional.of(moduleName);
    }

    private static boolean isApiPackage(
            String packageName,
            String rootPackage,
            String moduleName
    ) {
        String apiPackage = rootPackage + "." + moduleName + ".api";
        return packageName.equals(apiPackage)
                || packageName.startsWith(apiPackage + ".");
    }

    private static Path locateBackendRoot() {
        String workingDirectory = System.getProperty("user.dir");

        if (workingDirectory == null || workingDirectory.isBlank()) {
            throw new IllegalStateException(
                    "System property 'user.dir' bị thiếu hoặc rỗng; "
                            + "không thể tìm car-rental-api."
            );
        }

        Path current = Path.of(workingDirectory)
                .toAbsolutePath()
                .normalize();

        while (current != null) {
            if (isBackendRoot(current)) {
                return current;
            }

            Path nestedBackend = current.resolve("car-rental-api");

            if (isBackendRoot(nestedBackend)) {
                return nestedBackend;
            }

            current = current.getParent();
        }

        throw new IllegalStateException(
                "Không tìm thấy thư mục car-rental-api từ: "
                        + workingDirectory
        );
    }

    private static boolean isBackendRoot(Path candidate) {
        return Files.isRegularFile(candidate.resolve("pom.xml"))
                && Files.isDirectory(
                        candidate.resolve("src/main/java")
                )
                && Files.isDirectory(
                        candidate.resolve(
                                "src/test/java/com/carrental/architecture"
                        )
                );
    }
}
