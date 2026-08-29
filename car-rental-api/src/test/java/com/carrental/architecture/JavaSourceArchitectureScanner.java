package com.carrental.architecture;

import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.DoWhileLoopTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

final class JavaSourceArchitectureScanner {

    private static final String R5 = "R5";
    private static final String R6 = "R6";
    private static final String R9 = "R9";

    private static final Set<String> R5_FORBIDDEN_IMPORT_PREFIXES =
            Set.of(
                    "jakarta.persistence",
                    "javax.persistence",
                    "org.springframework.data.jpa"
            );

    private static final String R5_REMEDY =
            "Dùng NamedParameterJdbcTemplate, RowMapper và Native SQL; "
                    + "không dùng JPA hoặc Spring Data JPA. "
                    + "Xem module-architecture.md §1, §2 và ADR-0003.";

    private static final Set<String> R6_TARGET_TYPES =
            Set.of("OwnershipType", "RentalType");

    private static final Set<String> COMPARISON_METHODS =
            Set.of("equals", "compareTo");

    private static final String R6_REMEDY =
            "Chuyển phép so sánh hoặc rẽ nhánh vào lớp *PolicyResolver, "
                    + "phân giải policy một lần ở biên use case; xem "
                    + "module-architecture.md §7–§8 và ADR-0004.";

    private static final String CONFIGURATION_PORT =
            "ConfigurationPort";

    private static final Set<String> R9_RESTRICTED_MODULES =
            Set.of(
                    "booking",
                    "handover",
                    "payment",
                    "settlement"
            );

    private static final String R9_FIXTURE_PACKAGE_PREFIX =
            "com.carrental.architecture.fixtures.r9.";

    private static final String PRODUCTION_PACKAGE_PREFIX =
            "com.carrental.";

    private static final String R9_REMEDY =
            "Loại bỏ ConfigurationPort khỏi code xử lý đơn đã tồn tại; "
                    + "đọc các giá trị snapshot đã đóng băng trong đơn. "
                    + "Xem module-architecture.md §8 và ADR-0014.";

    private enum ScanMode {
        FULL,
        IMPORTS_ONLY
    }

    private JavaSourceArchitectureScanner() {
    }

    static SourceScanResult scan(
            Path projectRoot,
            Path sourceRoot
    ) {
        return scan(
                projectRoot,
                sourceRoot,
                ScanMode.FULL
        );
    }

    static List<ArchitectureViolation> scanForbiddenJpaImports(
            Path projectRoot,
            Path sourceRoot
    ) {
        return scan(
                projectRoot,
                sourceRoot,
                ScanMode.IMPORTS_ONLY
        ).r5Violations();
    }

    /**
     * Quét import bị cấm trong application mà không phân giải kiểu.
     *
     * <p>Fixture có thể import thư viện không tồn tại trên classpath.
     * Scanner vẫn kiểm cú pháp và báo lỗi nếu source không hợp lệ.
     *
     * @param projectRoot thư mục gốc dùng để hiển thị vị trí vi phạm
     * @param sourceRoot thư mục source cần kiểm
     * @return danh sách vi phạm R11 đã sắp xếp
     */
    static List<ArchitectureViolation> scanForbiddenApplicationImports(
            Path projectRoot,
            Path sourceRoot
    ) {
        return scan(
                projectRoot,
                sourceRoot,
                ScanMode.IMPORTS_ONLY
        ).r11Violations();
    }

    private static SourceScanResult scan(
            Path projectRoot,
            Path sourceRoot,
            ScanMode scanMode
    ) {
        Path normalizedProjectRoot = normalize(
                projectRoot,
                "projectRoot"
        );

        Path normalizedSourceRoot = normalizeSourceRoot(
                normalizedProjectRoot,
                sourceRoot
        );

        List<Path> sourceFiles = javaSourceFiles(
                normalizedSourceRoot
        );

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();

        if (compiler == null) {
            throw new IllegalStateException(
                    "Không tìm thấy JDK compiler. "
                            + "Hãy chạy test bằng JDK, không phải JRE."
            );
        }

        DiagnosticCollector<JavaFileObject> diagnostics =
                new DiagnosticCollector<>();

        List<ArchitectureViolation> r5Violations =
                new ArrayList<>();

        List<ArchitectureViolation> r6Violations =
                new ArrayList<>();

        List<ArchitectureViolation> r9Violations =
                new ArrayList<>();

        List<ArchitectureViolation> r11Violations =
                new ArrayList<>();

        try (StandardJavaFileManager fileManager =
                     compiler.getStandardFileManager(
                             diagnostics,
                             Locale.ROOT,
                             StandardCharsets.UTF_8
                     )) {
            Iterable<? extends JavaFileObject> javaFiles =
                    fileManager.getJavaFileObjectsFromPaths(
                            sourceFiles
                    );

            String classPath = System.getProperty(
                    "java.class.path"
            );

            if (classPath == null || classPath.isBlank()) {
                throw new IllegalStateException(
                        "java.class.path đang rỗng; "
                                + "không thể phân tích source Java."
                );
            }

            List<String> options = List.of(
                    "-proc:none",
                    "--release",
                    "25",
                    "-classpath",
                    classPath
            );

            JavacTask task = (JavacTask) compiler.getTask(
                    null,
                    fileManager,
                    diagnostics,
                    options,
                    null,
                    javaFiles
            );

            List<CompilationUnitTree> units =
                    new ArrayList<>();

            task.parse().forEach(units::add);

            throwOnCompilationErrors(
                    normalizedProjectRoot,
                    diagnostics
            );

            if (scanMode == ScanMode.FULL) {
                task.analyze();

                throwOnCompilationErrors(
                        normalizedProjectRoot,
                        diagnostics
                );
            }

            Trees trees = Trees.instance(task);

            for (CompilationUnitTree unit : units) {
                new R5Visitor(
                        trees,
                        unit,
                        normalizedProjectRoot,
                        r5Violations
                ).scanUnit();

                r11Violations.addAll(
                        R11ApplicationImportRule.inspect(
                                trees,
                                unit,
                                normalizedProjectRoot
                        )
                );

                if (scanMode == ScanMode.IMPORTS_ONLY) {
                    continue;
                }

                new R6Visitor(
                        trees,
                        unit,
                        normalizedProjectRoot,
                        r6Violations
                ).scan(unit, null);

                moduleName(unit).filter(
                        R9_RESTRICTED_MODULES::contains
                ).ifPresent(moduleName ->
                        new R9Visitor(
                                trees,
                                unit,
                                normalizedProjectRoot,
                                moduleName,
                                r9Violations
                        ).scanUnit()
                );
            }
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Không thể phân tích source Java trong "
                            + normalizedSourceRoot,
                    exception
            );
        }

        return new SourceScanResult(
                r5Violations.stream()
                        .sorted(ArchitectureViolation.ORDER)
                        .toList(),
                r6Violations.stream()
                        .sorted(ArchitectureViolation.ORDER)
                        .toList(),
                r9Violations.stream()
                        .sorted(ArchitectureViolation.ORDER)
                        .toList(),
                r11Violations.stream()
                        .sorted(ArchitectureViolation.ORDER)
                        .toList()
        );
    }

    /**
     * Chứa kết quả độc lập của từng rule quét source Java.
     *
     * @param r5Violations vi phạm import JPA trong persistence adapter
     * @param r6Violations vi phạm phân nhánh theo hai trục policy
     * @param r9Violations vi phạm đọc cấu hình trong module bị hạn chế
     * @param r11Violations vi phạm import công nghệ trong application
     */
    record SourceScanResult(
            List<ArchitectureViolation> r5Violations,
            List<ArchitectureViolation> r6Violations,
            List<ArchitectureViolation> r9Violations,
            List<ArchitectureViolation> r11Violations
    ) {

        /**
         * Bảo đảm các danh sách kết quả không null và không bị sửa sau khi tạo.
         */
        SourceScanResult {
            r5Violations = List.copyOf(
                    Objects.requireNonNull(
                            r5Violations,
                            "r5Violations"
                    )
            );

            r6Violations = List.copyOf(
                    Objects.requireNonNull(
                            r6Violations,
                            "r6Violations"
                    )
            );

            r9Violations = List.copyOf(
                    Objects.requireNonNull(
                            r9Violations,
                            "r9Violations"
                    )
            );

            r11Violations = List.copyOf(
                    Objects.requireNonNull(
                            r11Violations,
                            "r11Violations"
                    )
            );
        }
    }

    private static Optional<String> moduleName(
            CompilationUnitTree unit
    ) {
        if (unit.getPackageName() == null) {
            return Optional.empty();
        }

        String packageName = unit.getPackageName().toString();

        if (packageName.startsWith(R9_FIXTURE_PACKAGE_PREFIX)) {
            return firstPackageSegment(
                    packageName.substring(
                            R9_FIXTURE_PACKAGE_PREFIX.length()
                    )
            );
        }

        if (packageName.startsWith(PRODUCTION_PACKAGE_PREFIX)) {
            return firstPackageSegment(
                    packageName.substring(
                            PRODUCTION_PACKAGE_PREFIX.length()
                    )
            );
        }

        return Optional.empty();
    }

    private static Optional<String> firstPackageSegment(
            String remainder
    ) {
        int separator = remainder.indexOf('.');
        String segment = separator < 0
                ? remainder
                : remainder.substring(0, separator);

        return segment.isBlank()
                ? Optional.empty()
                : Optional.of(segment);
    }

    private static Path normalize(
            Path path,
            String name
    ) {
        Objects.requireNonNull(path, name);

        return path.toAbsolutePath().normalize();
    }

    private static Path normalizeSourceRoot(
            Path projectRoot,
            Path sourceRoot
    ) {
        Objects.requireNonNull(
                sourceRoot,
                "sourceRoot"
        );

        Path resolved = sourceRoot.isAbsolute()
                ? sourceRoot
                : projectRoot.resolve(sourceRoot);

        Path normalized = resolved
                .toAbsolutePath()
                .normalize();

        if (!Files.isDirectory(normalized)) {
            throw new IllegalArgumentException(
                    "Source root không tồn tại hoặc "
                            + "không phải thư mục: "
                            + normalized
            );
        }

        return normalized;
    }

    private static List<Path> javaSourceFiles(
            Path sourceRoot
    ) {
        try (var paths = Files.walk(sourceRoot)) {
            List<Path> sourceFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path
                            .getFileName()
                            .toString()
                            .endsWith(".java"))
                    .map(path -> path
                            .toAbsolutePath()
                            .normalize())
                    .sorted()
                    .toList();

            if (sourceFiles.isEmpty()) {
                throw new IllegalArgumentException(
                        "Không tìm thấy file .java "
                                + "trong source root: "
                                + sourceRoot
                );
            }

            return sourceFiles;
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Không thể đọc source root: "
                            + sourceRoot,
                    exception
            );
        }
    }

    private static void throwOnCompilationErrors(
            Path projectRoot,
            DiagnosticCollector<JavaFileObject> diagnostics
    ) {
        List<String> errors = diagnostics
                .getDiagnostics()
                .stream()
                .filter(diagnostic ->
                        diagnostic.getKind()
                                == Diagnostic.Kind.ERROR
                )
                .map(diagnostic -> formatDiagnostic(
                        projectRoot,
                        diagnostic
                ))
                .sorted()
                .toList();

        if (!errors.isEmpty()) {
            throw new IllegalStateException(
                    "JDK Compiler Tree API không thể "
                            + "analyze source:%n%s"
                            .formatted(
                                    String.join(
                                            System.lineSeparator(),
                                            errors
                                    )
                            )
            );
        }
    }

    private static String formatDiagnostic(
            Path projectRoot,
            Diagnostic<? extends JavaFileObject> diagnostic
    ) {
        String location = "<compiler>";

        if (diagnostic.getSource() != null) {
            try {
                Path sourcePath = Path.of(
                                diagnostic
                                        .getSource()
                                        .toUri()
                        )
                        .toAbsolutePath()
                        .normalize();

                location = displayPath(
                        projectRoot,
                        sourcePath
                );
            } catch (RuntimeException ignored) {
                location = diagnostic
                        .getSource()
                        .getName();
            }
        }

        return "%s:%d:%d: %s".formatted(
                location,
                diagnostic.getLineNumber(),
                diagnostic.getColumnNumber(),
                diagnostic.getMessage(Locale.ROOT)
        );
    }

    private static String displayPath(
            Path projectRoot,
            Path sourcePath
    ) {
        return sourcePath.startsWith(projectRoot)
                ? projectRoot
                .relativize(sourcePath)
                .toString()
                : sourcePath.toString();
    }

    private static boolean isPersistenceAdapterPackage(
            CompilationUnitTree unit
    ) {
        if (unit.getPackageName() == null) {
            return false;
        }

        String packageName =
                unit.getPackageName().toString();

        return ("." + packageName + ".")
                .contains(".adapter.out.persistence.");
    }

    private static boolean isForbiddenR5Import(
            String importedName
    ) {
        return R5_FORBIDDEN_IMPORT_PREFIXES
                .stream()
                .anyMatch(prefix ->
                        importedName.equals(prefix)
                                || importedName.startsWith(
                                prefix + "."
                        )
                );
    }

    private static final class R5Visitor
            extends TreePathScanner<Void, Void> {

        private final Trees trees;
        private final CompilationUnitTree unit;
        private final Path projectRoot;
        private final Path sourcePath;
        private final List<ArchitectureViolation> violations;

        private R5Visitor(
                Trees trees,
                CompilationUnitTree unit,
                Path projectRoot,
                List<ArchitectureViolation> violations
        ) {
            this.trees = trees;
            this.unit = unit;
            this.projectRoot = projectRoot;
            this.violations = violations;

            this.sourcePath = Path.of(
                            unit.getSourceFile().toUri()
                    )
                    .toAbsolutePath()
                    .normalize();
        }

        private void scanUnit() {
            if (isPersistenceAdapterPackage(unit)) {
                scan(unit, null);
            }
        }

        @Override
        public Void visitImport(
                ImportTree node,
                Void unused
        ) {
            String importedName = node
                    .getQualifiedIdentifier()
                    .toString();

            if (isForbiddenR5Import(importedName)) {
                addViolation(node, importedName);
            }

            return null;
        }

        private void addViolation(
                ImportTree node,
                String importedName
        ) {
            long start = trees
                    .getSourcePositions()
                    .getStartPosition(unit, node);

            long line = start == Diagnostic.NOPOS
                    ? Diagnostic.NOPOS
                    : unit.getLineMap().getLineNumber(start);

            String lineText = line == Diagnostic.NOPOS
                    ? "?"
                    : Long.toString(line);

            violations.add(
                    new ArchitectureViolation(
                            R5,
                            displayPath(
                                    projectRoot,
                                    sourcePath
                            ) + ":" + lineText,
                            "Persistence adapter import "
                                    + "JPA bị cấm: "
                                    + importedName
                                    + ".",
                            R5_REMEDY
                    )
            );
        }
    }


    private static final class R9Visitor
            extends TreePathScanner<Void, Void> {

        private final Trees trees;
        private final CompilationUnitTree unit;
        private final Path projectRoot;
        private final Path sourcePath;
        private final String moduleName;
        private final List<ArchitectureViolation> violations;
        private final Map<String, R9Reference> referencesByRole =
                new LinkedHashMap<>();

        private ImportTree configurationPortImport;

        private R9Visitor(
                Trees trees,
                CompilationUnitTree unit,
                Path projectRoot,
                String moduleName,
                List<ArchitectureViolation> violations
        ) {
            this.trees = trees;
            this.unit = unit;
            this.projectRoot = projectRoot;
            this.moduleName = moduleName;
            this.violations = violations;
            this.sourcePath = Path.of(
                            unit.getSourceFile().toUri()
                    )
                    .toAbsolutePath()
                    .normalize();
        }

        private void scanUnit() {
            scan(unit, null);

            if (referencesByRole.isEmpty()
                    && configurationPortImport != null) {
                addViolation(
                        configurationPortImport,
                        "import trong file"
                );
                return;
            }

            referencesByRole.values().forEach(reference ->
                    addViolation(
                            reference.tree(),
                            "lớp " + reference.roleName()
                    )
            );
        }

        @Override
        public Void visitImport(
                ImportTree node,
                Void unused
        ) {
            TreePath importedPath = new TreePath(
                    getCurrentPath(),
                    node.getQualifiedIdentifier()
            );

            if (isConfigurationPortReference(importedPath)) {
                configurationPortImport = node;
            }

            return null;
        }

        @Override
        public Void visitIdentifier(
                IdentifierTree node,
                Void unused
        ) {
            registerReference(node);
            return super.visitIdentifier(node, unused);
        }

        @Override
        public Void visitMemberSelect(
                MemberSelectTree node,
                Void unused
        ) {
            registerReference(node);
            return super.visitMemberSelect(node, unused);
        }

        private void registerReference(Tree node) {
            if (!isConfigurationPortReference(
                    getCurrentPath()
            )) {
                return;
            }

            sourceRole().ifPresent(role ->
                    referencesByRole.putIfAbsent(
                            role.key(),
                            new R9Reference(
                                    node,
                                    role.displayName()
                            )
                    )
            );
        }

        private Optional<R9SourceRole> sourceRole() {
            for (TreePath path = getCurrentPath();
                 path != null;
                 path = path.getParentPath()) {
                if (!(path.getLeaf()
                        instanceof ClassTree classTree)) {
                    continue;
                }

                Element element = trees.getElement(path);

                if (element instanceof TypeElement typeElement
                        && !typeElement.getQualifiedName()
                        .isEmpty()) {
                    String qualifiedName = typeElement
                            .getQualifiedName()
                            .toString();

                    return Optional.of(
                            new R9SourceRole(
                                    qualifiedName,
                                    qualifiedName
                            )
                    );
                }

                long start = trees
                        .getSourcePositions()
                        .getStartPosition(unit, classTree);

                String simpleName = classTree
                        .getSimpleName()
                        .toString();

                String displayName = simpleName.isBlank()
                        ? "<anonymous>"
                        : simpleName;

                String key = displayName + "@" + start;

                return Optional.of(
                        new R9SourceRole(
                                key,
                                displayName
                        )
                );
            }

            return Optional.empty();
        }

        private boolean isConfigurationPortReference(
                TreePath path
        ) {
            Element element = trees.getElement(path);

            if (isConfigurationPortElement(element)) {
                return true;
            }

            TypeMirror type = trees.getTypeMirror(path);

            return type instanceof DeclaredType declaredType
                    && isConfigurationPortElement(
                            declaredType.asElement()
                    );
        }

        private static boolean isConfigurationPortElement(
                Element element
        ) {
            for (Element current = element;
                 current != null;
                 current = current.getEnclosingElement()) {
                if (current instanceof TypeElement typeElement
                        && typeElement.getSimpleName()
                        .contentEquals(CONFIGURATION_PORT)) {
                    return true;
                }
            }

            return false;
        }

        private void addViolation(
                Tree node,
                String sourceRole
        ) {
            long start = trees
                    .getSourcePositions()
                    .getStartPosition(unit, node);

            long line = start == Diagnostic.NOPOS
                    ? Diagnostic.NOPOS
                    : unit.getLineMap().getLineNumber(start);

            String lineText = line == Diagnostic.NOPOS
                    ? "?"
                    : Long.toString(line);

            violations.add(
                    new ArchitectureViolation(
                            R9,
                            displayPath(
                                    projectRoot,
                                    sourcePath
                            ) + ":" + lineText,
                            "Module '"
                                    + moduleName
                                    + "' tham chiếu "
                                    + CONFIGURATION_PORT
                                    + " trong "
                                    + sourceRole
                                    + "; các module booking, "
                                    + "handover, payment và "
                                    + "settlement phải dùng bản "
                                    + "sao cấu hình trong đơn.",
                            R9_REMEDY
                    )
            );
        }

        private record R9Reference(
                Tree tree,
                String roleName
        ) {
        }

        private record R9SourceRole(
                String key,
                String displayName
        ) {
        }
    }

    private static final class R6Visitor
            extends TreePathScanner<Void, Void> {

        private final Trees trees;
        private final CompilationUnitTree unit;
        private final Path projectRoot;
        private final Path sourcePath;
        private final List<ArchitectureViolation> violations;

        private R6Visitor(
                Trees trees,
                CompilationUnitTree unit,
                Path projectRoot,
                List<ArchitectureViolation> violations
        ) {
            this.trees = trees;
            this.unit = unit;
            this.projectRoot = projectRoot;

            this.sourcePath = Path.of(
                            unit.getSourceFile().toUri()
                    )
                    .toAbsolutePath()
                    .normalize();

            this.violations = violations;
        }

        @Override
        public Void visitBinary(
                BinaryTree node,
                Void unused
        ) {
            if (!isPolicyResolver()
                    && isEqualityOperator(node.getKind())
                    && !isNullExpression(
                    node.getLeftOperand()
            )
                    && !isNullExpression(
                    node.getRightOperand()
            )) {
                Set<String> targetTypes = targetTypes(
                        node.getLeftOperand(),
                        node.getRightOperand()
                );

                if (!targetTypes.isEmpty()) {
                    addViolation(
                            node,
                            "So sánh ==/!= theo "
                                    + joined(targetTypes)
                                    + " ngoài lớp "
                                    + "*PolicyResolver."
                    );
                }
            }

            return super.visitBinary(node, unused);
        }

        @Override
        public Void visitMethodInvocation(
                MethodInvocationTree node,
                Void unused
        ) {
            if (isPolicyResolver()) {
                return super.visitMethodInvocation(
                        node,
                        unused
                );
            }

            String methodName = methodName(node);

            if (COMPARISON_METHODS.contains(methodName)) {
                List<ExpressionTree> operands =
                        comparisonOperands(node);

                Set<String> targetTypes =
                        targetTypes(operands);

                if (!targetTypes.isEmpty()
                        && !isNullOnlyComparison(
                        node,
                        operands
                )) {
                    addViolation(
                            node,
                            "So sánh bằng "
                                    + methodName
                                    + " theo "
                                    + joined(targetTypes)
                                    + " ngoài lớp "
                                    + "*PolicyResolver."
                    );
                }

                return super.visitMethodInvocation(
                        node,
                        unused
                );
            }

            ExpressionTree receiver = receiver(node);

            if (receiver != null
                    && !isTypeQualifier(receiver)
                    && isInsideDecisionExpression()) {
                String targetType = targetType(
                        childPath(receiver)
                );

                if (targetType != null) {
                    addViolation(
                            node,
                            "Gọi "
                                    + methodName
                                    + " trực tiếp trên "
                                    + targetType
                                    + " trong biểu thức "
                                    + "rẽ nhánh ngoài lớp "
                                    + "*PolicyResolver."
                    );
                }
            }

            return super.visitMethodInvocation(node, unused);
        }

        @Override
        public Void visitSwitch(
                SwitchTree node,
                Void unused
        ) {
            addSwitchViolation(
                    node,
                    node.getExpression()
            );

            return super.visitSwitch(node, unused);
        }

        @Override
        public Void visitSwitchExpression(
                SwitchExpressionTree node,
                Void unused
        ) {
            addSwitchViolation(
                    node,
                    node.getExpression()
            );

            return super.visitSwitchExpression(
                    node,
                    unused
            );
        }

        private void addSwitchViolation(
                Tree node,
                ExpressionTree selector
        ) {
            if (isPolicyResolver()) {
                return;
            }

            String targetType = targetType(
                    childPath(selector)
            );

            if (targetType != null) {
                addViolation(
                        node,
                        "Rẽ nhánh switch theo "
                                + targetType
                                + " ngoài lớp "
                                + "*PolicyResolver."
                );
            }
        }

        private boolean isPolicyResolver() {
            for (TreePath path = getCurrentPath();
                 path != null;
                 path = path.getParentPath()) {
                if (path.getLeaf()
                        instanceof ClassTree classTree) {
                    // Chỉ lớp khai báo gần nhất được hưởng ngoại lệ.
                    // Lớp lồng và lớp vô danh là đơn vị code riêng,
                    // không thừa hưởng vai trò PolicyResolver từ lớp cha.
                    return classTree
                            .getSimpleName()
                            .toString()
                            .endsWith(
                                    "PolicyResolver"
                            );
                }
            }

            return false;
        }

        private boolean isInsideDecisionExpression() {
            TreePath child = getCurrentPath();

            for (TreePath parent = child.getParentPath();
                 parent != null;
                 child = parent,
                         parent = parent.getParentPath()) {
                Tree parentTree = parent.getLeaf();
                Tree childTree = child.getLeaf();

                if (parentTree instanceof IfTree decision) {
                    return childTree
                            == decision.getCondition();
                }

                if (parentTree
                        instanceof ConditionalExpressionTree decision) {
                    return childTree
                            == decision.getCondition();
                }

                if (parentTree
                        instanceof WhileLoopTree decision) {
                    return childTree
                            == decision.getCondition();
                }

                if (parentTree
                        instanceof DoWhileLoopTree decision) {
                    return childTree
                            == decision.getCondition();
                }

                if (parentTree
                        instanceof ForLoopTree decision) {
                    return childTree
                            == decision.getCondition();
                }

                if (parentTree
                        instanceof SwitchTree decision) {
                    return childTree
                            == decision.getExpression();
                }

                if (parentTree
                        instanceof SwitchExpressionTree decision) {
                    return childTree
                            == decision.getExpression();
                }
            }

            return false;
        }

        private boolean isTypeQualifier(
                ExpressionTree expression
        ) {
            Element element = trees.getElement(
                    childPath(expression)
            );

            return element != null
                    && (element.getKind().isClass()
                    || element.getKind().isInterface());
        }

        private List<ExpressionTree> comparisonOperands(
                MethodInvocationTree node
        ) {
            List<ExpressionTree> operands =
                    new ArrayList<>();

            if (!isStatic(node)) {
                ExpressionTree receiver = receiver(node);

                if (receiver != null) {
                    operands.add(receiver);
                }
            }

            operands.addAll(node.getArguments());

            return operands;
        }

        private boolean isStatic(
                MethodInvocationTree node
        ) {
            Element element = trees.getElement(
                    childPath(node.getMethodSelect())
            );

            return element != null
                    && element.getModifiers()
                    .contains(Modifier.STATIC);
        }

        private boolean isNullOnlyComparison(
                MethodInvocationTree node,
                List<ExpressionTree> operands
        ) {
            int targetCount = 0;
            boolean allOtherOperandsAreNull = true;

            for (ExpressionTree operand : operands) {
                if (targetType(
                        childPath(operand)
                ) != null) {
                    targetCount++;
                } else if (!isNullExpression(operand)) {
                    allOtherOperandsAreNull = false;
                }
            }

            if (!isStatic(node)
                    && receiver(node) == null) {
                allOtherOperandsAreNull = false;
            }

            return targetCount == 1
                    && allOtherOperandsAreNull;
        }

        private Set<String> targetTypes(
                ExpressionTree... expressions
        ) {
            return targetTypes(
                    List.of(expressions)
            );
        }

        private Set<String> targetTypes(
                List<ExpressionTree> expressions
        ) {
            Set<String> names = new TreeSet<>();

            for (ExpressionTree expression : expressions) {
                String targetType = targetType(
                        childPath(expression)
                );

                if (targetType != null) {
                    names.add(targetType);
                }
            }

            return names;
        }

        private String targetType(TreePath path) {
            TypeMirror type = trees.getTypeMirror(path);

            if (!(type instanceof DeclaredType declaredType)) {
                return null;
            }

            Element element = declaredType.asElement();

            if (element.getKind() != ElementKind.ENUM) {
                return null;
            }

            String simpleName = element
                    .getSimpleName()
                    .toString();

            return R6_TARGET_TYPES.contains(simpleName)
                    ? simpleName
                    : null;
        }

        private TreePath childPath(Tree child) {
            return new TreePath(
                    getCurrentPath(),
                    child
            );
        }

        private void addViolation(
                Tree node,
                String description
        ) {
            long start = trees
                    .getSourcePositions()
                    .getStartPosition(unit, node);

            long line = start == Diagnostic.NOPOS
                    ? Diagnostic.NOPOS
                    : unit.getLineMap()
                    .getLineNumber(start);

            String lineText =
                    line == Diagnostic.NOPOS
                            ? "?"
                            : Long.toString(line);

            violations.add(
                    new ArchitectureViolation(
                            R6,
                            displayPath(
                                    projectRoot,
                                    sourcePath
                            ) + ":" + lineText,
                            description,
                            R6_REMEDY
                    )
            );
        }

        private static boolean isEqualityOperator(
                Tree.Kind kind
        ) {
            return kind == Tree.Kind.EQUAL_TO
                    || kind == Tree.Kind.NOT_EQUAL_TO;
        }

        private static boolean isNullExpression(
                ExpressionTree expression
        ) {
            return switch (expression.getKind()) {
                case NULL_LITERAL -> true;

                case PARENTHESIZED ->
                        isNullExpression(
                                ((ParenthesizedTree) expression)
                                        .getExpression()
                        );

                case TYPE_CAST ->
                        isNullExpression(
                                ((TypeCastTree) expression)
                                        .getExpression()
                        );

                default -> false;
            };
        }

        private static ExpressionTree receiver(
                MethodInvocationTree node
        ) {
            return node.getMethodSelect()
                    instanceof MemberSelectTree member
                    ? member.getExpression()
                    : null;
        }

        private static String methodName(
                MethodInvocationTree node
        ) {
            if (node.getMethodSelect()
                    instanceof MemberSelectTree member) {
                return member
                        .getIdentifier()
                        .toString();
            }

            if (node.getMethodSelect()
                    instanceof IdentifierTree identifier) {
                return identifier
                        .getName()
                        .toString();
            }

            return node.getMethodSelect().toString();
        }

        private static String joined(
                Set<String> targetTypes
        ) {
            return String.join("/", targetTypes);
        }
    }
}
