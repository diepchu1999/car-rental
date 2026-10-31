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
    private static final LicenseClassTable TABLE = new LicenseClassTable(Map.of("B", Set.of(4, 5, 7), "D1", Set.of(4, 5, 7, 16)));

    /** Bản VERIFIED hết hạn đúng ngày trả vẫn đủ điều kiện, ngày trước đó thì không; phủ cả hai loại. */
    @ParameterizedTest @CsvSource({"true,-1", "true,0", "true,1", "false,-1", "false,0", "false,1"})
    void expiryDateIsInclusive(boolean card, int offset) {
        var evidence = new RentalDocuments(card ? END.plusDays(offset) : END,
                card ? END : END.plusDays(offset), "B", false);
        if (offset < 0) assertError(evidence, 7, false, card ? ErrorCode.IDENTITY_CARD_EXPIRES_BEFORE_TRIP_END
                : ErrorCode.DRIVER_LICENSE_EXPIRES_BEFORE_TRIP_END, DomainException.Category.RULE_VIOLATION);
        else assertDoesNotThrow(() -> evidence.requireSelfDriveEligible(END, 7, false, TABLE));
    }

    /** Thiếu active là chưa xác minh, không diễn giải null thành giấy tờ không có hạn. */
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void missingVerifiedEvidenceIsForbidden(boolean card) {
        var evidence = card ? new RentalDocuments(null, END, "B", false) : new RentalDocuments(END, null, null, null);
        assertError(evidence, 7, false, card ? ErrorCode.IDENTITY_CARD_NOT_VERIFIED : ErrorCode.DRIVER_LICENSE_NOT_VERIFIED,
                DomainException.Category.FORBIDDEN);
    }

    /** B không lái 16 chỗ, nhưng D1 được phép; không áp hạn chế số chỗ của hạng này cho mọi hạng. */
    @Test void usesClassSeatMapping() {
        assertError(new RentalDocuments(END, END, "B", false), 16, false,
                ErrorCode.DRIVER_LICENSE_NOT_SUITABLE_FOR_VEHICLE, DomainException.Category.RULE_VIOLATION);
        assertDoesNotThrow(() -> new RentalDocuments(END, END, "D1", false).requireSelfDriveEligible(END, 16, true, TABLE));
    }

    /** Chỉ tổ hợp GPLX hạn chế tự động + xe số sàn bị chặn; không suy hạn chế từ tên hạng. */
    @ParameterizedTest @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void appliesAutomaticRestriction(boolean automaticOnly, boolean manual) {
        var evidence = new RentalDocuments(END, END, "B", automaticOnly);
        if (automaticOnly && manual) assertError(evidence, 5, true,
                ErrorCode.DRIVER_LICENSE_NOT_SUITABLE_FOR_VEHICLE, DomainException.Category.RULE_VIOLATION);
        else assertDoesNotThrow(() -> evidence.requireSelfDriveEligible(END, 5, manual, TABLE));
    }

    /** Hạng ngoài bảng có mã riêng, không tự đổi hoa, trim hoặc đoán quan hệ hạng. */
    @ParameterizedTest @ValueSource(strings = {"UNKNOWN", "b", " B"})
    void unknownClassHasDistinctError(String code) {
        assertError(new RentalDocuments(END, END, code, true), 16, true,
                ErrorCode.DRIVER_LICENSE_CLASS_NOT_SUPPORTED, DomainException.Category.RULE_VIOLATION);
    }

    /** Dữ liệu cấu hình quyết định quyền lái; hạng mới không đòi thêm nhánh Java. */
    @Test void acceptsNewConfiguredClass() {
        var custom = new LicenseClassTable(Map.of("LOCAL_TEST_CLASS", Set.of(16)));
        assertDoesNotThrow(() -> new RentalDocuments(END, END, "LOCAL_TEST_CLASS", false)
                .requireSelfDriveEligible(END, 16, true, custom));
    }

    /** Nhiều lỗi cùng lúc vẫn ưu tiên CCCD xác minh/hạn, GPLX xác minh/hạn rồi hạng. */
    @Test void preservesFailureOrder() {
        assertError(new RentalDocuments(null, null, null, null), 16, true,
                ErrorCode.IDENTITY_CARD_NOT_VERIFIED, DomainException.Category.FORBIDDEN);
        assertError(new RentalDocuments(END.minusDays(1), null, null, null), 16, true,
                ErrorCode.IDENTITY_CARD_EXPIRES_BEFORE_TRIP_END, DomainException.Category.RULE_VIOLATION);
        assertError(new RentalDocuments(END, END.minusDays(1), "UNKNOWN", true), 16, true,
                ErrorCode.DRIVER_LICENSE_EXPIRES_BEFORE_TRIP_END, DomainException.Category.RULE_VIOLATION);
        assertError(new RentalDocuments(END, END, "UNKNOWN", true), 16, true,
                ErrorCode.DRIVER_LICENSE_CLASS_NOT_SUPPORTED, DomainException.Category.RULE_VIOLATION);
    }

    /** Metadata hỏng không được làm null automaticOnly thành false hay bỏ qua hạng. */
    @Test void rejectsIncompleteLicenseMetadata() {
        assertThrows(IllegalArgumentException.class, () -> new RentalDocuments(END, END, "B", null));
        assertThrows(IllegalArgumentException.class, () -> new RentalDocuments(END, END, null, false));
        assertThrows(IllegalArgumentException.class, () -> new RentalDocuments(END, END, " ", false));
        assertThrows(IllegalArgumentException.class, () -> new RentalDocuments(END, null, "B", false));
    }

    /** Domain không tự in metadata nhạy cảm qua record.toString. */
    @Test void redactsEvidence() { assertEquals("RentalDocuments[redacted]", new RentalDocuments(END, END, "B", true).toString()); }

    /** Chứng minh đúng mã/nhóm và thông điệp cố định, không chỉ bắt một exception bất kỳ. */
    private static void assertError(RentalDocuments evidence, int seats, boolean manual, ErrorCode code, DomainException.Category category) {
        var failure = assertThrows(DomainException.class, () -> evidence.requireSelfDriveEligible(END, seats, manual, TABLE));
        assertEquals(code, failure.errorCode()); assertEquals(category, failure.category());
        assertEquals(code.defaultMessage(), failure.getMessage());
    }
}
