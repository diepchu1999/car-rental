package com.carrental.identity.domain;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** BR-106/106b/128/130: quy tắc thuần, gồm thứ tự lỗi và biên hạn ngày Tech Owner chốt 31/10/2026. */
class RentalDocumentsTest {
    private static final LocalDate END = LocalDate.of(2030, 1, 1);
    private static final LicenseClassPolicy POLICY = new LicenseClassPolicy(LocalDate.of(2025, 1, 1),
            new LicenseClassTable(Map.of("B1", Set.of(4, 5, 7), "D", Set.of(4, 5, 7, 16))),
            new LicenseClassTable(Map.of("B", Set.of(4, 5, 7), "D1", Set.of(4, 5, 7, 16))));

    /** Bản VERIFIED hết hạn đúng ngày trả vẫn đủ điều kiện, ngày trước đó thì không; phủ cả hai loại. */
    @ParameterizedTest @CsvSource({"true,-1", "true,0", "true,1", "false,-1", "false,0", "false,1"})
    void expiryDateIsInclusive(boolean card, int offset) {
        var evidence = new RentalDocuments(card ? END.plusDays(offset) : END,
                card ? END : END.plusDays(offset), "B", false, LocalDate.of(2025, 1, 1));
        if (offset < 0) assertError(evidence, 7, false, card ? ErrorCode.IDENTITY_CARD_EXPIRES_BEFORE_TRIP_END
                : ErrorCode.DRIVER_LICENSE_EXPIRES_BEFORE_TRIP_END, DomainException.Category.RULE_VIOLATION);
        else assertDoesNotThrow(() -> evidence.requireSelfDriveEligible(END, 7, false, POLICY));
    }

    /** Thiếu active là chưa xác minh, không diễn giải null thành giấy tờ không có hạn. */
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void missingVerifiedEvidenceIsForbidden(boolean card) {
        var evidence = card ? new RentalDocuments(null, END, "B", false, LocalDate.of(2025, 1, 1)) : new RentalDocuments(END, null, null, null, null);
        assertError(evidence, 7, false, card ? ErrorCode.IDENTITY_CARD_NOT_VERIFIED : ErrorCode.DRIVER_LICENSE_NOT_VERIFIED,
                DomainException.Category.FORBIDDEN);
    }

    /** B không lái 16 chỗ, nhưng D1 được phép; không áp hạn chế số chỗ của hạng này cho mọi hạng. */
    @Test void usesClassSeatMapping() {
        assertError(new RentalDocuments(END, END, "B", false, LocalDate.of(2025, 1, 1)), 16, false,
                ErrorCode.DRIVER_LICENSE_NOT_SUITABLE_FOR_VEHICLE, DomainException.Category.RULE_VIOLATION);
        assertDoesNotThrow(() -> new RentalDocuments(END, END, "D1", false, LocalDate.of(2025, 1, 1)).requireSelfDriveEligible(END, 16, true, POLICY));
    }

    /** Chỉ tổ hợp GPLX hạn chế tự động + xe số sàn bị chặn; không suy hạn chế từ tên hạng. */
    @ParameterizedTest @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void appliesAutomaticRestriction(boolean automaticOnly, boolean manual) {
        var evidence = new RentalDocuments(END, END, "B", automaticOnly, LocalDate.of(2025, 1, 1));
        if (automaticOnly && manual) assertError(evidence, 5, true,
                ErrorCode.DRIVER_LICENSE_NOT_SUITABLE_FOR_VEHICLE, DomainException.Category.RULE_VIOLATION);
        else assertDoesNotThrow(() -> evidence.requireSelfDriveEligible(END, 5, manual, POLICY));
    }

    /** Hạng ngoài bảng có mã riêng, không tự đổi hoa, trim hoặc đoán quan hệ hạng. */
    @ParameterizedTest @ValueSource(strings = {"UNKNOWN", "b", " B"})
    void unknownClassHasDistinctError(String code) {
        assertError(new RentalDocuments(END, END, code, true, LocalDate.of(2025, 1, 1)), 16, true,
                ErrorCode.DRIVER_LICENSE_CLASS_NOT_SUPPORTED, DomainException.Category.RULE_VIOLATION);
    }

    /** Dữ liệu cấu hình quyết định quyền lái; hạng mới không đòi thêm nhánh Java. */
    @Test void acceptsNewConfiguredClass() {
        var table = new LicenseClassTable(Map.of("LOCAL_TEST_CLASS", Set.of(16)));
        var custom = new LicenseClassPolicy(LocalDate.of(2025, 1, 1), table, table);
        assertDoesNotThrow(() -> new RentalDocuments(END, END, "LOCAL_TEST_CLASS", false, LocalDate.of(2025, 1, 1))
                .requireSelfDriveEligible(END, 16, true, custom));
    }

    /** Nhiều lỗi cùng lúc vẫn ưu tiên CCCD xác minh/hạn, GPLX xác minh/hạn rồi hạng. */
    @Test void preservesFailureOrder() {
        assertError(new RentalDocuments(null, null, null, null, null), 16, true,
                ErrorCode.IDENTITY_CARD_NOT_VERIFIED, DomainException.Category.FORBIDDEN);
        assertError(new RentalDocuments(END.minusDays(1), null, null, null, null), 16, true,
                ErrorCode.IDENTITY_CARD_EXPIRES_BEFORE_TRIP_END, DomainException.Category.RULE_VIOLATION);
        assertError(new RentalDocuments(END, END.minusDays(1), "UNKNOWN", true, LocalDate.of(2025, 1, 1)), 16, true,
                ErrorCode.DRIVER_LICENSE_EXPIRES_BEFORE_TRIP_END, DomainException.Category.RULE_VIOLATION);
        assertError(new RentalDocuments(END, END, "UNKNOWN", true, LocalDate.of(2025, 1, 1)), 16, true,
                ErrorCode.DRIVER_LICENSE_CLASS_NOT_SUPPORTED, DomainException.Category.RULE_VIOLATION);
    }

    /** BR-128: B1 cũ lái ô tô; đúng mốc hoặc sau mốc là B1 mới, không có trong bảng ô tô. */
    @ParameterizedTest @CsvSource({"2024-12-31,true", "2025-01-01,false", "2025-01-02,false"})
    void distinguishesB1ByIssueDate(String issuedOn, boolean eligible) {
        var evidence = new RentalDocuments(END, END, "B1", false, LocalDate.parse(issuedOn));
        if (eligible) assertDoesNotThrow(() -> evidence.requireSelfDriveEligible(END, 4, false, POLICY));
        else assertError(evidence, 4, false, ErrorCode.DRIVER_LICENSE_CLASS_NOT_SUPPORTED, DomainException.Category.RULE_VIOLATION);
    }

    /** GPLX cũ thiếu ngày cấp cần nộp lại; luôn là 403 chưa xác minh, không là lỗi hạng hoặc hết hạn. */
    @Test void missingIssueDateRequiresResubmission() {
        assertError(new RentalDocuments(END, END, "B", false, null), 4, false,
                ErrorCode.DRIVER_LICENSE_NOT_VERIFIED, DomainException.Category.FORBIDDEN);
        assertError(new RentalDocuments(END, END.minusDays(1), "UNKNOWN", true, null), 16, true,
                ErrorCode.DRIVER_LICENSE_NOT_VERIFIED, DomainException.Category.FORBIDDEN);
    }

    /** B1 cũ có trong bảng nhưng sai số chỗ/hạn chế hộp số dùng lỗi không phù hợp, không lỗi hạng lạ. */
    @Test void keepsOldLicenseSeatAndTransmissionRestrictions() {
        var issued = LocalDate.of(2024, 12, 31);
        assertError(new RentalDocuments(END, END, "B1", false, issued), 16, false,
                ErrorCode.DRIVER_LICENSE_NOT_SUITABLE_FOR_VEHICLE, DomainException.Category.RULE_VIOLATION);
        assertError(new RentalDocuments(END, END, "B1", true, issued), 4, true,
                ErrorCode.DRIVER_LICENSE_NOT_SUITABLE_FOR_VEHICLE, DomainException.Category.RULE_VIOLATION);
        assertDoesNotThrow(() -> new RentalDocuments(END, END, "B1", true, issued)
                .requireSelfDriveEligible(END, 4, false, POLICY));
    }

    /** Metadata hỏng không được làm null automaticOnly thành false hay bỏ qua hạng. */
    @Test void rejectsIncompleteLicenseMetadata() {
        assertThrows(IllegalArgumentException.class, () -> new RentalDocuments(END, END, "B", null, LocalDate.of(2025, 1, 1)));
        assertThrows(IllegalArgumentException.class, () -> new RentalDocuments(END, END, null, false, LocalDate.of(2025, 1, 1)));
        assertThrows(IllegalArgumentException.class, () -> new RentalDocuments(END, END, " ", false, LocalDate.of(2025, 1, 1)));
        assertThrows(IllegalArgumentException.class, () -> new RentalDocuments(END, null, "B", false, null));
    }

    /** Domain không tự in metadata nhạy cảm qua record.toString. */
    @Test void redactsEvidence() { assertEquals("RentalDocuments[redacted]", new RentalDocuments(END, END, "B", true, LocalDate.of(2025, 1, 1)).toString()); }

    /** Chứng minh đúng mã/nhóm và thông điệp cố định, không chỉ bắt một exception bất kỳ. */
    private static void assertError(RentalDocuments evidence, int seats, boolean manual, ErrorCode code, DomainException.Category category) {
        var failure = assertThrows(DomainException.class, () -> evidence.requireSelfDriveEligible(END, seats, manual, POLICY));
        assertEquals(code, failure.errorCode()); assertEquals(category, failure.category());
        assertEquals(code.defaultMessage(), failure.getMessage());
    }
}
