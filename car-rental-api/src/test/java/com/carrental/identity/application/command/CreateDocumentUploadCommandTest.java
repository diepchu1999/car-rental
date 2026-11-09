package com.carrental.identity.application.command;

import com.carrental.identity.domain.DocumentSide;
import com.carrental.identity.domain.DocumentType;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** BR-130: loại và mặt bắt buộc, không chuẩn hoá âm thầm dữ liệu sai. */
class CreateDocumentUploadCommandTest {
    /** Cả hai loại và hai mặt đều hợp lệ, không có trường chủ do client truyền. */
    @Test void acceptsEveryDocumentTypeAndSide() {
        for (var type : DocumentType.values()) for (var side : DocumentSide.values()) {
            assertEquals(new CreateDocumentUploadCommand(type, side),
                    CreateDocumentUploadCommand.from(type.name(), side.name()));
        }
    }

    /** Thiếu, enum lạ, sai hoa hoặc có khoảng trắng trả đúng INVALID_REQUEST, không echo giá trị. */
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" ", "secret-input", "identity_card", " FRONT"})
    void rejectsInvalidTypeOrSide(String value) {
        var type = assertThrows(DomainException.class, () -> CreateDocumentUploadCommand.from(value, "FRONT"));
        var side = assertThrows(DomainException.class, () -> CreateDocumentUploadCommand.from("IDENTITY_CARD", value));
        assertEquals(ErrorCode.INVALID_REQUEST, type.errorCode());
        assertEquals(ErrorCode.INVALID_REQUEST, side.errorCode());
        assertFalse(type.getMessage().contains("secret-input"));
        assertFalse(side.getMessage().contains("secret-input"));
    }

    /** Constructor không trở thành đường bỏ qua kiểm null của factory. */
    @Test void rejectsNullComponentsInConstructor() {
        assertThrows(DomainException.class, () -> new CreateDocumentUploadCommand(null, DocumentSide.FRONT));
        assertThrows(DomainException.class, () -> new CreateDocumentUploadCommand(DocumentType.IDENTITY_CARD, null));
    }
}
