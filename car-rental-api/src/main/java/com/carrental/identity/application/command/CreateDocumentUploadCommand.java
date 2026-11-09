package com.carrental.identity.application.command;

import com.carrental.identity.domain.DocumentSide;
import com.carrental.identity.domain.DocumentType;
import com.carrental.shared.error.DomainException;

/** BR-130: chỉ chọn loại giấy tờ và mặt ảnh, không nhận tài khoản hoặc object key từ client. */
public record CreateDocumentUploadCommand(DocumentType documentType, DocumentSide side) {
    /** Không cho bỏ qua validation bằng cách gọi constructor trực tiếp. */
    public CreateDocumentUploadCommand {
        if (documentType == null || side == null) {
            throw DomainException.invalidInput("documentType and side are required.");
        }
    }

    /** Nhận giá trị thô và kiểm enum chính xác, không phản chiếu đầu vào vào thông báo lỗi. */
    public static CreateDocumentUploadCommand from(String documentType, String side) {
        if (documentType == null || side == null) {
            throw DomainException.invalidInput("documentType and side are required.");
        }
        try {
            return new CreateDocumentUploadCommand(DocumentType.valueOf(documentType), DocumentSide.valueOf(side));
        } catch (IllegalArgumentException failure) {
            throw DomainException.invalidInput("Unsupported documentType or side.");
        }
    }
}
