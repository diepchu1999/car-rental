package com.carrental.architecture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SqlArchitectureScanner {

    private static final String R7 = "R7";
    private static final String R8 = "R8";

    private static final String UNCLASSIFIED_MODULE = "<unclassified>";

    private static final Set<String> KNOWN_MODULES = Set.of(
            "audit",
            "availability",
            "booking",
            "branch",
            "compliance",
            "config",
            "contract",
            "dispatch",
            "fleet",
            "handover",
            "identity",
            "incident",
            "messaging",
            "notification",
            "payment",
            "pricing",
            "review",
            "search",
            "settlement",
            "storage",
            "vehicle"
    );

    private static final Set<String> IMMUTABLE_EVIDENCE_MODULES =
            Set.of("handover", "compliance");

    private static final Pattern FLYWAY_FILE_NAME = Pattern.compile(
            "(?i)^(?:V[0-9][0-9._]*|U[0-9][0-9._]*|B[0-9][0-9._]*|R)"
                    + "__([a-z][a-z0-9]*)_.+\\.sql$"
    );

    private static final String R7_REMEDY =
            "Đưa thao tác ghi lịch xe qua API/port của module availability; "
                    + "chỉ availability được sở hữu và ghi bảng "
                    + "availability.reservation; xem module-architecture.md §6, §8 "
                    + "và ADR-0005.";

    private static final String R8_REMEDY =
            "Không xoá cứng hồ sơ bằng chứng; chỉ thêm phiên bản hoặc trạng thái mới "
                    + "và giữ nguyên dữ liệu cũ; xem module-architecture.md §8 "
                    + "và ADR-0015.";

    private SqlArchitectureScanner() {
    }

    static SqlScanResult scan(
            Path projectRoot,
            Path resourceRoot
    ) {
        Path normalizedProjectRoot = normalize(
                projectRoot,
                "projectRoot"
        );

        Path normalizedResourceRoot = normalizeResourceRoot(
                normalizedProjectRoot,
                resourceRoot
        );

        List<ArchitectureViolation> r7Violations =
                new ArrayList<>();
        List<ArchitectureViolation> r8Violations =
                new ArrayList<>();

        for (Path sqlFile : sqlFiles(normalizedResourceRoot)) {
            String sql = readOnce(sqlFile);
            String module = classifyModule(
                    normalizedResourceRoot,
                    sqlFile
            );
            String location = displayPath(
                    normalizedProjectRoot,
                    sqlFile
            );

            List<SqlToken> tokens = new SqlLexer(
                    sql,
                    location
            ).lex();

            inspectR7(
                    tokens,
                    module,
                    location,
                    r7Violations
            );
            inspectR8(
                    tokens,
                    module,
                    location,
                    r8Violations
            );
        }

        return new SqlScanResult(
                r7Violations.stream()
                        .sorted(ArchitectureViolation.ORDER)
                        .toList(),
                r8Violations.stream()
                        .sorted(ArchitectureViolation.ORDER)
                        .toList()
        );
    }

    record SqlScanResult(
            List<ArchitectureViolation> r7Violations,
            List<ArchitectureViolation> r8Violations
    ) {

        SqlScanResult {
            r7Violations = List.copyOf(
                    Objects.requireNonNull(
                            r7Violations,
                            "r7Violations"
                    )
            );
            r8Violations = List.copyOf(
                    Objects.requireNonNull(
                            r8Violations,
                            "r8Violations"
                    )
            );
        }
    }

    private static Path normalize(
            Path path,
            String name
    ) {
        Objects.requireNonNull(path, name);

        return path.toAbsolutePath().normalize();
    }

    private static Path normalizeResourceRoot(
            Path projectRoot,
            Path resourceRoot
    ) {
        Objects.requireNonNull(
                resourceRoot,
                "resourceRoot"
        );

        Path resolved = resourceRoot.isAbsolute()
                ? resourceRoot
                : projectRoot.resolve(resourceRoot);

        Path normalized = resolved
                .toAbsolutePath()
                .normalize();

        if (!Files.isDirectory(normalized)) {
            throw new IllegalArgumentException(
                    "Resource root không tồn tại hoặc không phải thư mục: "
                            + normalized
            );
        }

        return normalized;
    }

    private static List<Path> sqlFiles(Path resourceRoot) {
        try (var paths = Files.walk(resourceRoot)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path
                            .getFileName()
                            .toString()
                            .toLowerCase(Locale.ROOT)
                            .endsWith(".sql"))
                    .map(path -> path
                            .toAbsolutePath()
                            .normalize())
                    .sorted()
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Không thể liệt kê file SQL trong resource root: "
                            + resourceRoot,
                    exception
            );
        }
    }

    private static String readOnce(Path sqlFile) {
        try {
            return Files.readString(
                    sqlFile,
                    StandardCharsets.UTF_8
            );
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Không thể đọc file SQL: " + sqlFile,
                    exception
            );
        }
    }

    private static String classifyModule(
            Path resourceRoot,
            Path sqlFile
    ) {
        Path relative = resourceRoot.relativize(sqlFile);

        for (int index = 0;
             index + 1 < relative.getNameCount() - 1;
             index++) {
            String part = lower(relative.getName(index));

            if (part.equals("sql")) {
                String candidate = lower(
                        relative.getName(index + 1)
                );

                if (KNOWN_MODULES.contains(candidate)) {
                    return candidate;
                }
            }
        }

        for (int index = 0;
             index + 2 < relative.getNameCount() - 1;
             index++) {
            String first = lower(relative.getName(index));
            String second = lower(relative.getName(index + 1));

            if (first.equals("db") && second.equals("migration")) {
                String candidate = lower(
                        relative.getName(index + 2)
                );

                if (KNOWN_MODULES.contains(candidate)) {
                    return candidate;
                }
            }
        }

        Matcher matcher = FLYWAY_FILE_NAME.matcher(
                sqlFile.getFileName().toString()
        );

        if (matcher.matches()) {
            String candidate = matcher
                    .group(1)
                    .toLowerCase(Locale.ROOT);

            if (KNOWN_MODULES.contains(candidate)) {
                return candidate;
            }
        }

        return UNCLASSIFIED_MODULE;
    }

    private static String lower(Path part) {
        return part
                .toString()
                .toLowerCase(Locale.ROOT);
    }

    private static void inspectR7(
            List<SqlToken> tokens,
            String module,
            String fileLocation,
            List<ArchitectureViolation> violations
    ) {
        if (module.equals("availability")) {
            return;
        }

        for (int index = 0; index < tokens.size(); index++) {
            SqlToken token = tokens.get(index);
            String operation = null;
            boolean writesReservation = false;

            if (token.isKeyword("INSERT")) {
                operation = "INSERT";
                writesReservation = targetAfterKeyword(
                        tokens,
                        index + 1,
                        "INTO"
                );
            } else if (token.isKeyword("UPDATE")) {
                operation = "UPDATE";
                writesReservation = targetAfterOptionalOnly(
                        tokens,
                        index + 1
                );
            } else if (token.isKeyword("DELETE")) {
                operation = "DELETE";
                writesReservation = targetAfterKeywordAndOptionalOnly(
                        tokens,
                        index + 1,
                        "FROM"
                );
            } else if (token.isKeyword("MERGE")) {
                operation = "MERGE";
                writesReservation = targetAfterKeyword(
                        tokens,
                        index + 1,
                        "INTO"
                );
            } else if (token.isKeyword("TRUNCATE")) {
                operation = "TRUNCATE";
                writesReservation = truncateTargetsReservation(
                        tokens,
                        index + 1
                );
            } else if (token.isKeyword("COPY")) {
                operation = "COPY FROM";
                writesReservation = copyFromTargetsReservation(
                        tokens,
                        index + 1
                );
            }

            if (writesReservation) {
                violations.add(
                        new ArchitectureViolation(
                                R7,
                                atLine(fileLocation, token.line()),
                                "%s trong SQL của module '%s' ghi trực tiếp vào "
                                        .formatted(operation, module)
                                        + "availability.reservation; chỉ module "
                                        + "availability được phép ghi bảng khoá lịch.",
                                R7_REMEDY
                        )
                );
            }
        }
    }

    private static void inspectR8(
            List<SqlToken> tokens,
            String module,
            String fileLocation,
            List<ArchitectureViolation> violations
    ) {
        for (int index = 0; index < tokens.size(); index++) {
            SqlToken token = tokens.get(index);
            String operation = null;

            if (token.isKeyword("DELETE")) {
                if (isMergeThenDelete(tokens, index)) {
                    operation = "MERGE ... THEN DELETE";
                } else if (hasKeyword(
                        tokens,
                        index + 1,
                        "FROM"
                )) {
                    operation = "DELETE";
                }
            } else if (token.isKeyword("TRUNCATE")) {
                operation = "TRUNCATE";
            } else if (token.isKeyword("DROP")
                    && hasKeyword(tokens, index + 1, "TABLE")) {
                operation = "DROP TABLE";
            }

            boolean belongsToEvidenceModule =
                    IMMUTABLE_EVIDENCE_MODULES.contains(module);

            boolean targetsEvidenceSchema = operation != null
                    && r8OperationTargetsEvidenceSchema(
                            tokens,
                            index,
                            operation
                    );

            if (operation != null
                    && (belongsToEvidenceModule
                    || targetsEvidenceSchema)) {
                String targetExplanation = belongsToEvidenceModule
                        ? ""
                        : " nhằm vào schema handover/compliance";

                violations.add(
                        new ArchitectureViolation(
                                R8,
                                atLine(fileLocation, token.line()),
                                "SQL của module '%s' chứa thao tác huỷ bằng chứng bị cấm: %s%s."
                                        .formatted(
                                                module,
                                                operation,
                                                targetExplanation
                                        ),
                                R8_REMEDY
                        )
                );
            }
        }
    }

    private static boolean r8OperationTargetsEvidenceSchema(
            List<SqlToken> tokens,
            int operationIndex,
            String operation
    ) {
        return switch (operation) {
            case "DELETE" -> targetSchemaAfterKeywordAndOptionalOnly(
                    tokens,
                    operationIndex + 1,
                    "FROM"
            );
            case "MERGE ... THEN DELETE" ->
                    mergeTargetUsesEvidenceSchema(
                            tokens,
                            operationIndex
                    );
            case "TRUNCATE" -> statementTargetsEvidenceSchema(
                    tokens,
                    operationIndex + 1
            );
            case "DROP TABLE" -> dropTableTargetsEvidenceSchema(
                    tokens,
                    operationIndex + 2
            );
            default -> false;
        };
    }

    private static boolean mergeTargetUsesEvidenceSchema(
            List<SqlToken> tokens,
            int deleteIndex
    ) {
        for (int current = deleteIndex - 2;
             current >= 0
                     && tokens.get(current).kind()
                     != TokenKind.SEMICOLON;
             current--) {
            if (tokens.get(current).isKeyword("MERGE")) {
                return targetSchemaAfterKeywordAndOptionalOnly(
                        tokens,
                        current + 1,
                        "INTO"
                );
            }
        }

        return false;
    }

    private static boolean targetSchemaAfterKeywordAndOptionalOnly(
            List<SqlToken> tokens,
            int index,
            String keyword
    ) {
        if (!hasKeyword(tokens, index, keyword)) {
            return false;
        }

        int targetIndex = hasKeyword(tokens, index + 1, "ONLY")
                ? index + 2
                : index + 1;

        return isEvidenceSchemaTarget(tokens, targetIndex);
    }

    private static boolean dropTableTargetsEvidenceSchema(
            List<SqlToken> tokens,
            int index
    ) {
        int targetIndex = index;

        if (hasKeyword(tokens, targetIndex, "IF")
                && hasKeyword(tokens, targetIndex + 1, "EXISTS")) {
            targetIndex += 2;
        }

        return statementTargetsEvidenceSchema(
                tokens,
                targetIndex
        );
    }

    private static boolean statementTargetsEvidenceSchema(
            List<SqlToken> tokens,
            int index
    ) {
        int statementEnd = statementEnd(tokens, index);

        for (int current = index;
             current < statementEnd;
             current++) {
            if (isEvidenceSchemaTarget(tokens, current)) {
                return true;
            }
        }

        return false;
    }

    private static boolean isEvidenceSchemaTarget(
            List<SqlToken> tokens,
            int index
    ) {
        if (index < 0 || index >= tokens.size()) {
            return false;
        }

        SqlToken schema = tokens.get(index);

        return hasKind(tokens, index + 1, TokenKind.DOT)
                && IMMUTABLE_EVIDENCE_MODULES.stream()
                .anyMatch(schema::isIdentifier);
    }

    private static boolean isMergeThenDelete(
            List<SqlToken> tokens,
            int deleteIndex
    ) {
        if (!hasKeyword(tokens, deleteIndex - 1, "THEN")) {
            return false;
        }

        for (int current = deleteIndex - 2;
             current >= 0
                     && tokens.get(current).kind()
                     != TokenKind.SEMICOLON;
             current--) {
            if (tokens.get(current).isKeyword("MERGE")) {
                return true;
            }
        }

        return false;
    }

    private static boolean targetAfterKeyword(
            List<SqlToken> tokens,
            int index,
            String keyword
    ) {
        return hasKeyword(tokens, index, keyword)
                && targetAfterOptionalOnly(tokens, index + 1);
    }

    private static boolean targetAfterOptionalOnly(
            List<SqlToken> tokens,
            int index
    ) {
        int targetIndex = hasKeyword(tokens, index, "ONLY")
                ? index + 1
                : index;

        return isReservationTarget(tokens, targetIndex);
    }

    private static boolean targetAfterKeywordAndOptionalOnly(
            List<SqlToken> tokens,
            int index,
            String keyword
    ) {
        if (!hasKeyword(tokens, index, keyword)) {
            return false;
        }

        return targetAfterOptionalOnly(tokens, index + 1);
    }

    private static boolean truncateTargetsReservation(
            List<SqlToken> tokens,
            int index
    ) {
        int cursor = hasKeyword(tokens, index, "TABLE")
                ? index + 1
                : index;

        int statementEnd = statementEnd(tokens, cursor);

        for (int current = cursor;
             current < statementEnd;
             current++) {
            if (isReservationTarget(tokens, current)) {
                return true;
            }
        }

        return false;
    }

    private static boolean copyFromTargetsReservation(
            List<SqlToken> tokens,
            int targetIndex
    ) {
        if (!isReservationTarget(tokens, targetIndex)) {
            return false;
        }

        int statementEnd = statementEnd(tokens, targetIndex);

        for (int current = targetIndex + 3;
             current < statementEnd;
             current++) {
            if (tokens.get(current).isKeyword("FROM")) {
                return true;
            }
        }

        return false;
    }

    private static int statementEnd(
            List<SqlToken> tokens,
            int start
    ) {
        int current = Math.max(0, start);

        while (current < tokens.size()
                && tokens.get(current).kind()
                != TokenKind.SEMICOLON) {
            current++;
        }

        return current;
    }

    private static boolean isReservationTarget(
            List<SqlToken> tokens,
            int index
    ) {
        return hasIdentifier(tokens, index, "availability")
                && hasKind(tokens, index + 1, TokenKind.DOT)
                && hasIdentifier(tokens, index + 2, "reservation");
    }

    private static boolean hasKeyword(
            List<SqlToken> tokens,
            int index,
            String keyword
    ) {
        return index >= 0
                && index < tokens.size()
                && tokens.get(index).isKeyword(keyword);
    }

    private static boolean hasIdentifier(
            List<SqlToken> tokens,
            int index,
            String identifier
    ) {
        return index >= 0
                && index < tokens.size()
                && tokens.get(index).isIdentifier(identifier);
    }

    private static boolean hasKind(
            List<SqlToken> tokens,
            int index,
            TokenKind kind
    ) {
        return index >= 0
                && index < tokens.size()
                && tokens.get(index).kind() == kind;
    }

    private static String atLine(
            String fileLocation,
            int line
    ) {
        return fileLocation + ":" + line;
    }

    private static String displayPath(
            Path projectRoot,
            Path sourcePath
    ) {
        return sourcePath.startsWith(projectRoot)
                ? projectRoot.relativize(sourcePath).toString()
                : sourcePath.toString();
    }

    private enum TokenKind {
        WORD,
        QUOTED_IDENTIFIER,
        DOT,
        LEFT_PARENTHESIS,
        RIGHT_PARENTHESIS,
        COMMA,
        SEMICOLON,
        OTHER
    }

    private record SqlToken(
            TokenKind kind,
            String text,
            int line,
            int column
    ) {

        private SqlToken {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(text, "text");
        }

        private boolean isKeyword(String keyword) {
            return kind == TokenKind.WORD
                    && text.equalsIgnoreCase(keyword);
        }

        private boolean isIdentifier(String identifier) {
            if (kind == TokenKind.WORD) {
                return text.equalsIgnoreCase(identifier);
            }

            return kind == TokenKind.QUOTED_IDENTIFIER
                    && text.equals(identifier);
        }
    }

    private static final class SqlLexer {

        private final String sql;
        private final String fileLocation;
        private final List<SqlToken> tokens = new ArrayList<>();

        private int index;
        private int line = 1;
        private int column = 1;

        private SqlLexer(
                String sql,
                String fileLocation
        ) {
            this.sql = Objects.requireNonNull(sql, "sql");
            this.fileLocation = Objects.requireNonNull(
                    fileLocation,
                    "fileLocation"
            );
        }

        private List<SqlToken> lex() {
            while (!atEnd()) {
                if (Character.isWhitespace(current())) {
                    advance();
                } else if (startsWith("--")) {
                    skipLineComment();
                } else if (startsWith("/*")) {
                    skipNestedBlockComment();
                } else if (current() == '\'') {
                    skipSingleQuotedString(
                            backslashEscapesSingleQuotedString()
                    );
                } else if (current() == '"') {
                    readQuotedIdentifier();
                } else if (current() == '$'
                        && dollarDelimiter() != null) {
                    skipDollarQuotedString(dollarDelimiter());
                } else if (isWordStart(current())) {
                    readWord();
                } else {
                    readSymbol();
                }
            }

            return List.copyOf(tokens);
        }

        private void skipLineComment() {
            advance();
            advance();

            while (!atEnd() && current() != '\n') {
                advance();
            }
        }

        private void skipNestedBlockComment() {
            int startLine = line;
            int startColumn = column;
            int depth = 0;

            while (!atEnd()) {
                if (startsWith("/*")) {
                    depth++;
                    advance();
                    advance();
                } else if (startsWith("*/")) {
                    depth--;
                    advance();
                    advance();

                    if (depth == 0) {
                        return;
                    }
                } else {
                    advance();
                }
            }

            throw unterminated(
                    startLine,
                    startColumn,
                    "block comment /* ... */"
            );
        }

        private void skipSingleQuotedString(
                boolean backslashEscapes
        ) {
            int startLine = line;
            int startColumn = column;
            advance();

            while (!atEnd()) {
                if (current() == '\'' && peek(1) == '\'') {
                    advance();
                    advance();
                } else if (current() == '\'') {
                    advance();
                    return;
                } else if (backslashEscapes
                        && current() == '\\'
                        && peek(1) != '\0') {
                    advance();
                    advance();
                } else {
                    advance();
                }
            }

            throw unterminated(
                    startLine,
                    startColumn,
                    "single-quoted string"
            );
        }

        private boolean backslashEscapesSingleQuotedString() {
            if (index >= 1
                    && (sql.charAt(index - 1) == 'E'
                    || sql.charAt(index - 1) == 'e')
                    && (index == 1
                    || !isWordPart(sql.charAt(index - 2)))) {
                return true;
            }

            return index >= 2
                    && sql.charAt(index - 1) == '&'
                    && (sql.charAt(index - 2) == 'U'
                    || sql.charAt(index - 2) == 'u')
                    && (index == 2
                    || !isWordPart(sql.charAt(index - 3)));
        }

        private void readQuotedIdentifier() {
            int startLine = line;
            int startColumn = column;
            StringBuilder value = new StringBuilder();
            advance();

            while (!atEnd()) {
                if (current() == '"' && peek(1) == '"') {
                    value.append('"');
                    advance();
                    advance();
                } else if (current() == '"') {
                    advance();
                    tokens.add(new SqlToken(
                            TokenKind.QUOTED_IDENTIFIER,
                            value.toString(),
                            startLine,
                            startColumn
                    ));
                    return;
                } else {
                    value.append(current());
                    advance();
                }
            }

            throw unterminated(
                    startLine,
                    startColumn,
                    "quoted identifier"
            );
        }

        private void skipDollarQuotedString(String delimiter) {
            int startLine = line;
            int startColumn = column;
            advance(delimiter.length());

            while (!atEnd()) {
                if (startsWith(delimiter)) {
                    advance(delimiter.length());
                    return;
                }

                advance();
            }

            throw unterminated(
                    startLine,
                    startColumn,
                    "dollar-quoted string " + delimiter
            );
        }

        private String dollarDelimiter() {
            if (current() != '$') {
                return null;
            }

            int cursor = index + 1;

            if (cursor < sql.length() && sql.charAt(cursor) == '$') {
                return "$$";
            }

            if (cursor >= sql.length()
                    || !isDollarTagStart(sql.charAt(cursor))) {
                return null;
            }

            cursor++;

            while (cursor < sql.length()
                    && isDollarTagPart(sql.charAt(cursor))) {
                cursor++;
            }

            if (cursor < sql.length() && sql.charAt(cursor) == '$') {
                return sql.substring(index, cursor + 1);
            }

            return null;
        }

        private void readWord() {
            int start = index;
            int startLine = line;
            int startColumn = column;

            advance();

            while (!atEnd() && isWordPart(current())) {
                advance();
            }

            tokens.add(new SqlToken(
                    TokenKind.WORD,
                    sql.substring(start, index),
                    startLine,
                    startColumn
            ));
        }

        private void readSymbol() {
            int startLine = line;
            int startColumn = column;
            char symbol = current();
            TokenKind kind = switch (symbol) {
                case '.' -> TokenKind.DOT;
                case '(' -> TokenKind.LEFT_PARENTHESIS;
                case ')' -> TokenKind.RIGHT_PARENTHESIS;
                case ',' -> TokenKind.COMMA;
                case ';' -> TokenKind.SEMICOLON;
                default -> TokenKind.OTHER;
            };

            tokens.add(new SqlToken(
                    kind,
                    Character.toString(symbol),
                    startLine,
                    startColumn
            ));
            advance();
        }

        private IllegalStateException unterminated(
                int startLine,
                int startColumn,
                String construct
        ) {
            return new IllegalStateException(
                    "Không thể quét SQL tại %s:%d:%d: %s chưa đóng."
                            .formatted(
                                    fileLocation,
                                    startLine,
                                    startColumn,
                                    construct
                            )
            );
        }

        private boolean startsWith(String expected) {
            return sql.startsWith(expected, index);
        }

        private boolean atEnd() {
            return index >= sql.length();
        }

        private char current() {
            return sql.charAt(index);
        }

        private char peek(int distance) {
            int target = index + distance;

            return target < sql.length()
                    ? sql.charAt(target)
                    : '\0';
        }

        private void advance(int count) {
            for (int consumed = 0; consumed < count; consumed++) {
                advance();
            }
        }

        private void advance() {
            char consumed = sql.charAt(index++);

            if (consumed == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }

        private static boolean isWordStart(char value) {
            return value == '_'
                    || Character.isLetter(value);
        }

        private static boolean isWordPart(char value) {
            return value == '_'
                    || value == '$'
                    || Character.isLetterOrDigit(value);
        }

        private static boolean isDollarTagStart(char value) {
            return value == '_'
                    || Character.isLetter(value);
        }

        private static boolean isDollarTagPart(char value) {
            return value == '_'
                    || Character.isLetterOrDigit(value);
        }
    }
}
