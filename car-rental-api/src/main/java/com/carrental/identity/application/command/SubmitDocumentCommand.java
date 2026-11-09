package com.carrental.identity.application.command;

import com.carrental.identity.domain.DocumentNumber;
import com.carrental.identity.domain.DocumentType;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.validation.Validations;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * BR-120/130: thông tin khách tự khai và hai mã ảnh cho một lần nộp, chưa phải giấy tờ đã xác minh.
 * Không nhận chủ, trạng thái, người duyệt, object key hoặc URL; use case phải kiểm chủ ảnh từ caller.
 * Số giấy tờ chỉ ở bộ nhớ và phải đi qua DocumentNumberCryptoPort trước khi ghi CSDL.
 */
public record SubmitDocumentCommand(DocumentType documentType, DocumentNumber number,
        String fullName, LocalDate birthDate, LocalDate expiresOn,
        String licenseClass, Boolean automaticOnly, String frontUploadCode, String backUploadCode, LocalDate issuedOn) {

    /**
     * Giữ validation kể cả khi gọi constructor trực tiếp; hai bộ trường không được trộn lẫn.
     * Không tự xét tuổi, thời hạn thuê hoặc hạng phù hợp xe ở bước khách khai giấy tờ.
     */
    public SubmitDocumentCommand {
        Validations.required(documentType, "documentType");
        Validations.required(number, "number");
        Validations.required(expiresOn, "expiresOn");
        requireUploadCode(frontUploadCode, "frontUploadCode");
        requireUploadCode(backUploadCode, "backUploadCode");
        if (frontUploadCode.equals(backUploadCode)) {
            throw DomainException.invalidInput("Front and back must use different upload codes.");
        }
        switch (documentType) {
            case IDENTITY_CARD -> {
                requireSafeText(fullName, "fullName");
                Validations.required(birthDate, "birthDate");
                if (licenseClass != null || automaticOnly != null || issuedOn != null) {
                    throw DomainException.invalidInput("Driver license fields are not allowed for an identity card.");
                }
            }
            case DRIVER_LICENSE -> {
                requireSafeText(licenseClass, "licenseClass");
                // Boolean bắt buộc: thiếu không được tự hiểu là false.
                Validations.required(automaticOnly, "automaticOnly");
                Validations.required(issuedOn, "issuedOn");
                if (issuedOn.isAfter(expiresOn)) {
                    throw DomainException.invalidInput("issuedOn must not be after expiresOn.");
                }
                if (fullName != null || birthDate != null) {
                    throw DomainException.invalidInput("Identity card fields are not allowed for a driver license.");
                }
            }
        }
    }

    /**
     * Nhận giá trị thô, parse ngày ISO và enum nhưng không phụ thuộc DTO/Jackson.
     * Ngày hết hạn đã qua vẫn là dữ liệu tự khai; xác minh và cổng thuê xét hiệu lực theo BR-130/106b.
     */
    public static SubmitDocumentCommand from(String documentType, String number, String fullName,
            String birthDate, String expiresOn, String licenseClass, Boolean automaticOnly,
            String frontUploadCode, String backUploadCode, String issuedOn) {
        return new SubmitDocumentCommand(parseType(documentType), new DocumentNumber(number), fullName,
                parseDate(birthDate, "birthDate"), parseDate(expiresOn, "expiresOn"), licenseClass,
                automaticOnly, frontUploadCode, backUploadCode, parseDate(issuedOn, "issuedOn"));
    }

    /** Chỉ nhận enum đúng chính tả; bỏ exception gốc để không mang giá trị khách nhập vào log. */
    private static DocumentType parseType(String value) {
        Validations.required(value, "documentType");
        try {
            return DocumentType.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            throw DomainException.invalidInput("Unsupported documentType.");
        }
    }

    /** Null được kiểm theo loại giấy tờ ở constructor; chuỗi có mặt phải là ngày ISO hợp lệ. */
    private static LocalDate parseDate(String value, String fieldName) {
        if (value == null) return null;
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException ignored) {
            // Message/cause của parser chứa đầu vào; chỉ giữ tên trường cố định do code truyền.
            throw DomainException.invalidInput(fieldName + " must be a valid ISO date.");
        }
    }

    /** Chỉ kiểm định dạng AN; tồn tại, chủ, loại, mặt và sử dụng lại phải kiểm ở use case/CSDL. */
    private static void requireUploadCode(String value, String fieldName) {
        Validations.requiredText(value, fieldName);
        if (!value.matches("AN-[A-Z0-9]{6}")) {
            throw DomainException.invalidInput(fieldName + " must be a valid upload business code.");
        }
    }

    /** Văn bản bắt buộc, không ký tự điều khiển/surrogate hỏng; giữ nguyên nội dung hợp lệ. */
    private static void requireSafeText(String value, String fieldName) {
        Validations.requiredText(value, fieldName);
        if (value.codePoints().anyMatch(Character::isISOControl)
                || !StandardCharsets.UTF_8.newEncoder().canEncode(value)) {
            throw DomainException.invalidInput(fieldName + " must contain valid text without control characters.");
        }
    }

    /** Không để số, họ tên, ngày sinh, hạng hay mã ảnh lộ qua toString mặc định của record. */
    @Override
    public String toString() { return "SubmitDocumentCommand[redacted]"; }
}
