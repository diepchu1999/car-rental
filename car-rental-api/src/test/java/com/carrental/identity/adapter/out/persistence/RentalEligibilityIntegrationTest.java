package com.carrental.identity.adapter.out.persistence;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.carrental.PostgresTestConfiguration;
import com.carrental.identity.api.RentalEligibilityDirectory;
import com.carrental.identity.domain.DocumentType;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.shared.sql.SensitiveSql;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** BR-106/106b/128/130: API nội bộ → use case → domain → adapter SQL/PostgreSQL và cấu hình thật. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
@Transactional
class RentalEligibilityIntegrationTest extends DocumentDatabaseTestSupport {
    private static final Instant LAST_INSTANT = Instant.parse("2030-01-01T16:59:59.999999Z");
    private static final Instant NEXT_DAY = Instant.parse("2030-01-01T17:00:00Z");
    @Autowired RentalEligibilityDirectory eligibility;
    @Autowired Environment environment;

    /** D1 đã khai báo cho cả bốn số chỗ, GPLX không hạn chế hộp số; gọi ngoài HTTP vẫn hoạt động. */
    @ParameterizedTest @ValueSource(ints = {4, 5, 7, 16})
    void acceptsConfiguredClassForAllSupportedSeats(int seats) {
        long owner = eligibleOwner("D1", false);
        assertDoesNotThrow(() -> eligibility.requireSelfDriveEligible(accountCode(owner), seats, "MANUAL", LAST_INSTANT));
    }

    /** Phủ đủ ba lỗi hạng/hộp số bằng dữ liệu thật, không giả lỗi ở port hoặc vô tình chọn hạng khác. */
    @ParameterizedTest @CsvSource({"B,false,16,AUTOMATIC,DRIVER_LICENSE_NOT_SUITABLE_FOR_VEHICLE",
            "B,true,7,MANUAL,DRIVER_LICENSE_NOT_SUITABLE_FOR_VEHICLE", "LOCAL_UNKNOWN,false,7,AUTOMATIC,DRIVER_LICENSE_CLASS_NOT_SUPPORTED"})
    void rejectsUnsuitableLicense(String licenseClass, boolean automaticOnly, int seats, String transmission, ErrorCode error) {
        long owner = eligibleOwner(licenseClass, automaticOnly);
        assertError(() -> eligibility.requireSelfDriveEligible(accountCode(owner), seats, transmission, LAST_INSTANT), error);
    }

    /** GPLX hạn chế tự động vẫn dùng được với xe tự động, không chặn cả tài khoản. */
    @Test void acceptsAutomaticOnlyLicenseForAutomaticVehicle() {
        long owner = eligibleOwner("B", true);
        assertDoesNotThrow(() -> eligibility.requireSelfDriveEligible(accountCode(owner), 7, "AUTOMATIC", LAST_INSTANT));
    }

    /** BR-128: SQL lưu đúng ngày cấp; B1 mới không thuê ô tô, B1 cũ hợp lệ, biên bằng thuộc bảng mới. */
    @ParameterizedTest @CsvSource({"2024-12-31,true", "2025-01-01,false", "2025-01-02,false"})
    void checksB1IssueDateThroughRealGate(String date, boolean eligible) {
        long owner = newAccount();
        active(owner, submission(owner, "IDENTITY_CARD", "VERIFIED"));
        active(owner, submission(owner, "DRIVER_LICENSE", "VERIFIED").addValue("licenseClass", "B1").addValue("issuedOn", date));
        if (eligible) assertDoesNotThrow(() -> eligibility.requireSelfDriveEligible(accountCode(owner), 4, "AUTOMATIC", LAST_INSTANT));
        else assertError(() -> eligibility.requireSelfDriveEligible(accountCode(owner), 4, "AUTOMATIC", LAST_INSTANT),
                ErrorCode.DRIVER_LICENSE_CLASS_NOT_SUPPORTED);
    }

    /** NULL ở bản cũ không được suy thành bảng cũ hay ngày mặc định, dù latest mới đã có ngày cấp. */
    @ParameterizedTest @ValueSource(strings = {"PENDING", "REJECTED"})
    void legacyActiveWithoutIssueDateRequiresResubmission(String latestStatus) {
        long owner = newAccount();
        active(owner, submission(owner, "IDENTITY_CARD", "VERIFIED"));
        long legacy = insert("submission", submission(owner, "DRIVER_LICENSE", "VERIFIED").addValue("issuedOn", null));
        long latest = insert("submission", submission(owner, "DRIVER_LICENSE", latestStatus));
        insert("head", head(owner, "DRIVER_LICENSE", latest, legacy));
        assertError(() -> check(owner, LAST_INSTANT), ErrorCode.DRIVER_LICENSE_NOT_VERIFIED);
    }

    /** Cùng hạng B1 nhưng khác ngày: pending/rejected không được đổi chế độ của bản active, kiểm cả hai chiều. */
    @ParameterizedTest @CsvSource({"2024-12-31,2025-01-01,PENDING,true", "2024-12-31,2025-01-01,REJECTED,true",
            "2025-01-01,2024-12-31,PENDING,false", "2025-01-01,2024-12-31,REJECTED,false"})
    void selectsRegimeFromActiveRatherThanLatest(String activeDate, String latestDate, String status, boolean eligible) {
        long owner = newAccount();
        active(owner, submission(owner, "IDENTITY_CARD", "VERIFIED"));
        long activeId = insert("submission", submission(owner, "DRIVER_LICENSE", "VERIFIED")
                .addValue("licenseClass", "B1").addValue("issuedOn", activeDate));
        long latest = insert("submission", submission(owner, "DRIVER_LICENSE", status)
                .addValue("licenseClass", "B1").addValue("issuedOn", latestDate));
        insert("head", head(owner, "DRIVER_LICENSE", latest, activeId));
        if (eligible) assertDoesNotThrow(() -> check(owner, LAST_INSTANT));
        else assertError(() -> check(owner, LAST_INSTANT), ErrorCode.DRIVER_LICENSE_CLASS_NOT_SUPPORTED);
    }

    /** Khi bản thay thế đã VERIFIED và head chuyển sang nó, cổng phải dùng ngày cấp mới ngay lần gọi sau. */
    @Test void followsNewIssueDateAfterVerifiedReplacement() {
        long owner = newAccount();
        active(owner, submission(owner, "IDENTITY_CARD", "VERIFIED"));
        active(owner, submission(owner, "DRIVER_LICENSE", "VERIFIED")
                .addValue("licenseClass", "B1").addValue("issuedOn", "2024-12-31"));
        check(owner, LAST_INSTANT);
        long replacement = insert("submission", submission(owner, "DRIVER_LICENSE", "VERIFIED")
                .addValue("licenseClass", "B1").addValue("issuedOn", "2025-01-01"));
        jdbc.update(documentSql("update_head"), head(owner, "DRIVER_LICENSE", replacement, replacement));
        assertError(() -> check(owner, LAST_INSTANT), ErrorCode.DRIVER_LICENSE_CLASS_NOT_SUPPORTED);
    }

    /** Bản đang dùng còn hạn tới đúng cuối ngày D; giờ trả từ 00:00 D+1 bị từ chối cho đúng loại. */
    @ParameterizedTest @EnumSource(DocumentType.class)
    void permitsExpiryDayAndRejectsFollowingVietnamMidnight(DocumentType type) {
        long owner = newAccount();
        for (var candidate : DocumentType.values()) {
            active(owner, submission(owner, candidate.name(), "VERIFIED")
                    .addValue("expiresOn", candidate == type ? "2030-01-01" : "2031-01-01"));
        }
        assertDoesNotThrow(() -> check(owner, LAST_INSTANT));
        assertError(() -> check(owner, NEXT_DAY), expiryError(type));
    }

    /** Bản chưa VERIFIED không được đi qua cổng, dù đầy đủ thông tin và hạn xa trong tương lai. */
    @ParameterizedTest @CsvSource({"IDENTITY_CARD,PENDING", "IDENTITY_CARD,REJECTED", "IDENTITY_CARD,SUPERSEDED",
            "DRIVER_LICENSE,PENDING", "DRIVER_LICENSE,REJECTED", "DRIVER_LICENSE,SUPERSEDED"})
    void rejectsUnverifiedActiveAbsence(DocumentType type, String status) {
        long owner = newAccount();
        for (var candidate : DocumentType.values()) {
            if (candidate == type) {
                long id = insert("submission", submission(owner, type.name(), status).addValue("expiresOn", "2040-01-01"));
                insert("head", head(owner, type.name(), id, null));
            } else active(owner, submission(owner, candidate.name(), "VERIFIED"));
        }
        assertError(() -> check(owner, LAST_INSTANT), type == DocumentType.IDENTITY_CARD
                ? ErrorCode.IDENTITY_CARD_NOT_VERIFIED : ErrorCode.DRIVER_LICENSE_NOT_VERIFIED);
    }

    /** Bản mới đang chờ/bị từ chối không thay active hợp lệ, kể cả metadata bản mới không đạt điều kiện. */
    @ParameterizedTest @CsvSource({"IDENTITY_CARD,PENDING", "IDENTITY_CARD,REJECTED", "DRIVER_LICENSE,PENDING", "DRIVER_LICENSE,REJECTED"})
    void pendingOrRejectedReplacementDoesNotBlockEligibleOwner(DocumentType type, String status) {
        long owner = newAccount();
        for (var candidate : DocumentType.values()) {
            var verified = submission(owner, candidate.name(), "VERIFIED");
            if (candidate != type) active(owner, verified);
            else {
                long verifiedId = insert("submission", verified);
                var replacement = submission(owner, type.name(), status).addValue("expiresOn", "2000-01-01")
                        .addValue("submittedAt", "2026-10-14T03:00:00Z");
                if (status.equals("REJECTED")) replacement.addValue("reviewedAt", "2026-10-14T04:00:00Z");
                if (type == DocumentType.DRIVER_LICENSE) replacement.addValue("licenseClass", "LOCAL_UNKNOWN")
                        .addValue("automaticOnly", true).addValue("issuedOn", "1999-01-01");
                insert("head", head(owner, type.name(), insert("submission", replacement), verifiedId));
            }
        }
        assertDoesNotThrow(() -> check(owner, LAST_INSTANT));
    }

    /** Không quay lại bản lịch sử có hạn xa hơn khi bản active được chọn không còn hạn tới cuối chuyến. */
    @ParameterizedTest @EnumSource(DocumentType.class)
    void expiredActiveDoesNotFallBackToHistory(DocumentType type) {
        long owner = newAccount();
        for (var candidate : DocumentType.values()) {
            if (candidate != type) active(owner, submission(owner, candidate.name(), "VERIFIED"));
            else {
                insert("submission", submission(owner, type.name(), "VERIFIED").addValue("expiresOn", "2040-01-01"));
                active(owner, submission(owner, type.name(), "VERIFIED").addValue("expiresOn", "2029-12-31"));
            }
        }
        assertError(() -> check(owner, LAST_INSTANT), expiryError(type));
    }

    /** Không cache kết quả qua lần gọi; thay active thì lần kiểm sau phải dùng hạn của bản mới. */
    @ParameterizedTest @EnumSource(DocumentType.class)
    void followsReplacementOnNextCheck(DocumentType type) {
        long owner = eligibleOwner("B", false); check(owner, LAST_INSTANT);
        long next = insert("submission", submission(owner, type.name(), "VERIFIED").addValue("expiresOn", "2029-12-31"));
        jdbc.update(documentSql("update_head"), head(owner, type.name(), next, next));
        assertError(() -> check(owner, LAST_INSTANT), expiryError(type));
    }

    /** Không mượn giấy tờ của tài khoản khác; thiếu cả hai loại luôn báo CCCD trước. */
    @Test void isolatesOwnerAndPreservesMissingDocumentPriority() {
        long eligible = eligibleOwner("D1", false); check(eligible, LAST_INSTANT);
        long empty = newAccount();
        assertError(() -> check(empty, LAST_INSTANT), ErrorCode.IDENTITY_CARD_NOT_VERIFIED);
        assertError(() -> eligibility.requireSelfDriveEligible("TK-NONE00", 7, "MANUAL", LAST_INSTANT), ErrorCode.IDENTITY_CARD_NOT_VERIFIED);
    }

    /** Toàn đường gọi chỉ SELECT metadata với tham số được che; không phát sinh mở hồ sơ/audit/ảnh qua proxy. */
    @Test void onlyReadsRedactedMetadataThroughRealGate() {
        long owner = eligibleOwner("B", false); String ownerCode = accountCode(owner);
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var logs = new ListAppender<ILoggingEvent>(); logs.setContext(root.getLoggerContext()); logs.start(); root.addAppender(logs);
        try {
            eligibility.requireSelfDriveEligible(ownerCode, 7, "MANUAL", LAST_INSTANT);
            assertFalse(SensitiveSql.isActive());
            for (var event : logs.list) assertFalse(event.getFormattedMessage().contains(ownerCode));
            var sql = logs.list.stream().filter(event -> event.getLoggerName().equals("p6spy")
                    && event.getThreadName().equals(Thread.currentThread().getName())).toList();
            if (environment.acceptsProfiles(Profiles.of("sql-log"))) {
                assertEquals(1, sql.size());
                assertTrue(sql.getFirst().getFormattedMessage().contains("[REDACTED sensitive SQL]"));
            } else assertEquals(List.of(), sql);
        } finally { root.detachAppender(logs); logs.stop(); }
    }

    /** Dựng hai active theo schema thật, không tự tắt unique/foreign key/check. */
    private long eligibleOwner(String licenseClass, boolean automaticOnly) {
        long owner = newAccount(); active(owner, submission(owner, "IDENTITY_CARD", "VERIFIED"));
        active(owner, submission(owner, "DRIVER_LICENSE", "VERIFIED").addValue("licenseClass", licenseClass).addValue("automaticOnly", automaticOnly));
        return owner;
    }

    /** Chốt cả latest và active cùng bản VERIFIED; trường hợp thay thế tự đặt con trỏ khác trong test. */
    private void active(long owner, MapSqlParameterSource parameters) {
        long id = insert("submission", parameters); insert("head", head(owner, (String) parameters.getValue("type"), id, id));
    }

    /** Dùng xe 7 chỗ số sàn để bắt cả lỗi null/true automaticOnly vô tình được bỏ qua. */
    private void check(long owner, Instant end) { eligibility.requireSelfDriveEligible(accountCode(owner), 7, "MANUAL", end); }

    /** Hai loại có mã hết hạn riêng; không so bằng chuỗi thông báo dễ đổi. */
    private static ErrorCode expiryError(DocumentType type) {
        return type == DocumentType.IDENTITY_CARD ? ErrorCode.IDENTITY_CARD_EXPIRES_BEFORE_TRIP_END : ErrorCode.DRIVER_LICENSE_EXPIRES_BEFORE_TRIP_END;
    }

    /** Kiểm đúng mã, nhóm HTTP và thông điệp không chứa metadata tài khoản/giấy tờ. */
    private static void assertError(org.junit.jupiter.api.function.Executable action, ErrorCode code) {
        var failure = assertThrows(DomainException.class, action);
        assertEquals(code, failure.errorCode()); assertEquals(code.defaultMessage(), failure.getMessage());
        assertEquals(code == ErrorCode.IDENTITY_CARD_NOT_VERIFIED || code == ErrorCode.DRIVER_LICENSE_NOT_VERIFIED
                ? DomainException.Category.FORBIDDEN : DomainException.Category.RULE_VIOLATION, failure.category());
    }
}
