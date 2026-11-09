package com.carrental.identity.application.command;

import com.carrental.identity.domain.DocumentNumber;
import com.carrental.identity.domain.DocumentType;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** BR-130: chỉ validate hợp đồng nộp, không coi dữ liệu tự khai là đã xác minh hay đủ điều kiện thuê. */
class SubmitDocumentCommandTest {
    /** CCCD giữ tên/ngày; số dùng chuẩn hoá tối thiểu của DocumentNumber và đủ hai AN khác nhau. */
    @Test void acceptsIdentityCardFields() {
        var payload = identityCard();
        payload.put("number", "  00123-Ab 45  ");
        var command = submit(payload);
        assertEquals(DocumentType.IDENTITY_CARD, command.documentType());
        assertEquals("00123-Ab 45", command.number().value());
        assertEquals("Local Test Person", command.fullName());
        assertEquals(LocalDate.of(1995, 2, 28), command.birthDate());
        assertEquals(LocalDate.of(2035, 12, 31), command.expiresOn());
        assertNull(command.licenseClass());
        assertNull(command.automaticOnly());
        assertNull(command.issuedOn());
        assertEquals("AN-FRONT1", command.frontUploadCode());
        assertEquals("AN-BACK01", command.backUploadCode());
    }

    /** false là câu trả lời hợp lệ, khác với bỏ trống; true cũng giữ nguyên, không suy từ hạng. */
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void acceptsExplicitAutomaticOnly(boolean automaticOnly) {
        var payload = driverLicense();
        payload.put("automaticOnly", automaticOnly);
        var command = submit(payload);
        assertEquals(DocumentType.DRIVER_LICENSE, command.documentType());
        assertEquals("B", command.licenseClass());
        assertEquals(LocalDate.of(2025, 1, 1), command.issuedOn());
        assertEquals(Boolean.valueOf(automaticOnly), command.automaticOnly());
        assertNull(command.fullName());
        assertNull(command.birthDate());
    }

    /** Trường chung, trường riêng và ảnh bắt buộc đều bị chặn khi thiếu. */
    @ParameterizedTest @MethodSource("missingRequiredFields")
    void rejectsMissingRequiredFields(String type, String field) {
        var payload = type.equals("IDENTITY_CARD") ? identityCard() : driverLicense();
        payload.remove(field);
        assertInvalid(payload);
    }

    /** Dữ liệu bắt buộc theo đúng bảng trường BR-130, không thêm trường do backend tự đoán. */
    private static Stream<Arguments> missingRequiredFields() {
        return Stream.concat(
                Stream.of("documentType", "number", "expiresOn", "frontUploadCode", "backUploadCode", "fullName", "birthDate")
                        .map(field -> Arguments.of("IDENTITY_CARD", field)),
                Stream.of("number", "expiresOn", "frontUploadCode", "backUploadCode", "licenseClass", "automaticOnly", "issuedOn")
                        .map(field -> Arguments.of("DRIVER_LICENSE", field)));
    }

    /** Không nhận bộ trường của loại khác, kể cả chuỗi rỗng hoặc Boolean false. */
    @Test void rejectsFieldsFromOtherDocumentType() {
        for (Object value : new Object[]{"B", ""}) {
            var payload = identityCard(); payload.put("licenseClass", value); assertInvalid(payload);
        }
        var identity = identityCard(); identity.put("automaticOnly", false); assertInvalid(identity);
        identity = identityCard(); identity.put("issuedOn", "2025-01-01"); assertInvalid(identity);
        var license = driverLicense(); license.put("fullName", "Local Other Person"); assertInvalid(license);
        license = driverLicense(); license.put("birthDate", "1995-02-28"); assertInvalid(license);
    }

    /** Enum lạ/sai hoa/khoảng trắng không được chuẩn hoá thành một loại khác. */
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"PASSPORT", "identity_card", " IDENTITY_CARD", "private-type"})
    void rejectsInvalidDocumentType(String value) {
        var payload = identityCard(); payload.put("documentType", value); assertInvalid(payload);
    }

    /** Ngày không có thật hoặc sai ISO không được tự sửa; lỗi không giữ cause chứa ngày khách nhập. */
    @ParameterizedTest @ValueSource(strings = {"", "2025-02-29", "2026-04-31", "31/12/2035", "2035-12-31T00:00:00Z", " 2035-12-31", "private-date"})
    void rejectsInvalidDatesWithoutRetainingParserInput(String value) {
        var license = driverLicense(); license.put("issuedOn", value); assertInvalid(license);
        for (String field : new String[]{"birthDate", "expiresOn"}) {
            var payload = identityCard(); payload.put(field, value); assertInvalid(payload);
        }
    }

    /** BR-130: ngày cấp bằng ngày hết hạn hợp lệ; sau ngày hết hạn phải trả INVALID_REQUEST. */
    @Test void validatesIssueDateAgainstExpiry() {
        var payload = driverLicense(); payload.put("issuedOn", "2035-12-31");
        assertEquals(LocalDate.of(2035, 12, 31), submit(payload).issuedOn());
        payload.put("issuedOn", "2036-01-01"); assertInvalid(payload);
    }

    /** Không tự đặt hạn nộp hoặc độ tuổi: kiểm hiệu lực thuộc xác minh/cổng thuê, không thuộc parser. */
    @Test void doesNotInventEligibilityAtSubmission() {
        var identity = identityCard(); identity.put("expiresOn", "2000-01-01");
        assertEquals(LocalDate.of(2000, 1, 1), submit(identity).expiresOn());
        identity.put("birthDate", "2024-02-29");
        assertEquals(LocalDate.of(2024, 2, 29), submit(identity).birthDate());
        var license = driverLicense(); license.put("licenseClass", "Future-Class");
        assertEquals("Future-Class", submit(license).licenseClass());
    }

    /** Tên và hạng không rỗng/điều khiển/UTF-16 hỏng; không lộ giá trị sai ra lỗi. */
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" ", "\u2003", "private-text\n", "private\ttext", "\uD800"})
    void rejectsMissingOrUnsafeText(String value) {
        var identity = identityCard(); identity.put("fullName", value); assertInvalid(identity);
        var license = driverLicense(); license.put("licenseClass", value); assertInvalid(license);
    }

    /** Không tự đổi hoa/thường hoặc chuẩn hoá nội dung tên/hạng trước khi người xác minh đối chiếu. */
    @Test void preservesValidText() {
        var identity = identityCard(); identity.put("fullName", "  Local Test Person  ");
        assertEquals("  Local Test Person  ", submit(identity).fullName());
        var license = driverLicense(); license.put("licenseClass", " b ");
        assertEquals(" b ", submit(license).licenseClass());
    }

    /** Mã ảnh phải là AN-6 chính xác, không nhận ID số, mã GT hoặc object key. */
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"1", "GT-FRONT1", "an-front1", "AN-12345", "AN-1234567", " AN-FRONT1", "incoming/private-key"})
    void rejectsInvalidUploadCodes(String value) {
        for (String field : new String[]{"frontUploadCode", "backUploadCode"}) {
            var payload = identityCard(); payload.put(field, value); assertInvalid(payload);
        }
    }

    /** Một AN gắn cố định một mặt, không thể đóng vai cả FRONT và BACK trong cùng bản nộp. */
    @Test void rejectsSameUploadForBothSides() {
        var payload = identityCard(); payload.put("backUploadCode", payload.get("frontUploadCode"));
        assertInvalid(payload);
    }

    /** Số giấy tờ đi qua value object, không bị factory bỏ qua kiểm rỗng/ký tự điều khiển. */
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" ", "private-number\n", "\uD800"})
    void validatesDocumentNumber(String value) {
        var payload = identityCard(); payload.put("number", value); assertInvalid(payload);
    }

    /** Constructor trực tiếp vẫn bảo vệ đủ trường/loại/mã, không chỉ factory mới validate. */
    @Test void constructorCannotBypassValidation() {
        var number = new DocumentNumber("local-number");
        var date = LocalDate.of(2035, 12, 31);
        assertThrows(DomainException.class, () -> new SubmitDocumentCommand(null, number,
                "Local Person", date, date, null, null, "AN-FRONT1", "AN-BACK01", null));
        assertThrows(DomainException.class, () -> new SubmitDocumentCommand(DocumentType.IDENTITY_CARD, null,
                "Local Person", date, date, null, null, "AN-FRONT1", "AN-BACK01", null));
        assertThrows(DomainException.class, () -> new SubmitDocumentCommand(DocumentType.IDENTITY_CARD, number,
                null, date, date, null, null, "AN-FRONT1", "AN-BACK01", null));
        assertThrows(DomainException.class, () -> new SubmitDocumentCommand(DocumentType.DRIVER_LICENSE, number,
                null, null, date, "B", null, "AN-FRONT1", "AN-BACK01", LocalDate.of(2025, 1, 1)));
        assertThrows(DomainException.class, () -> new SubmitDocumentCommand(DocumentType.DRIVER_LICENSE, number,
                null, null, null, "B", false, "AN-FRONT1", "AN-BACK01", LocalDate.of(2025, 1, 1)));
        assertThrows(DomainException.class, () -> new SubmitDocumentCommand(DocumentType.DRIVER_LICENSE, number,
                null, null, date, "B", false, "AN-FRONT1", "AN-FRONT1", LocalDate.of(2025, 1, 1)));
    }

    /** Record không in thông tin giấy tờ hoặc mã ảnh vào log. */
    @Test void redactsCommandToString() {
        assertEquals("SubmitDocumentCommand[redacted]", submit(identityCard()).toString());
        assertEquals("SubmitDocumentCommand[redacted]", submit(driverLicense()).toString());
    }

    /** CCCD giả, không dùng số giấy tờ thật trong fixture. */
    private static Map<String, Object> identityCard() {
        var values = common();
        values.put("documentType", "IDENTITY_CARD"); values.put("fullName", "Local Test Person");
        values.put("birthDate", "1995-02-28");
        return values;
    }

    /** GPLX giả, không hardcode bảng hạng được phép thuê trong command. */
    private static Map<String, Object> driverLicense() {
        var values = common(); values.put("documentType", "DRIVER_LICENSE");
        values.put("licenseClass", "B"); values.put("automaticOnly", false); values.put("issuedOn", "2025-01-01");
        return values;
    }

    /** Các trường chung dùng dữ liệu thử và AN đúng cấu trúc. */
    private static Map<String, Object> common() {
        var values = new LinkedHashMap<String, Object>();
        values.put("number", "local-test-number"); values.put("expiresOn", "2035-12-31");
        values.put("frontUploadCode", "AN-FRONT1"); values.put("backUploadCode", "AN-BACK01");
        return values;
    }

    /** Gọi factory thật bằng dữ liệu thô, không đưa request DTO hoặc mock framework vào test. */
    private static SubmitDocumentCommand submit(Map<String, Object> values) {
        return SubmitDocumentCommand.from((String) values.get("documentType"), (String) values.get("number"),
                (String) values.get("fullName"), (String) values.get("birthDate"), (String) values.get("expiresOn"),
                (String) values.get("licenseClass"), (Boolean) values.get("automaticOnly"),
                (String) values.get("frontUploadCode"), (String) values.get("backUploadCode"), (String) values.get("issuedOn"));
    }

    /** Đường âm phải đúng INVALID_REQUEST, không giữ cause hoặc dữ liệu thử nhạy cảm trong message. */
    private static void assertInvalid(Map<String, Object> values) {
        var error = assertThrows(DomainException.class, () -> submit(values));
        assertEquals(ErrorCode.INVALID_REQUEST, error.errorCode());
        assertNull(error.getCause());
        assertFalse(error.toString().contains("private-"));
        assertFalse(error.toString().contains("local-test-number"));
        assertFalse(error.toString().contains("Local Test Person"));
    }
}
