package com.carrental.identity.application.port.out;

import com.carrental.identity.domain.DocumentUpload;

/** Cổng ghi lượt tải BR-130; application sở hữu transaction và sinh mã AN. */
public interface WriteDocumentUploadPort {
    /**
     * Trả true khi đã chèn trong transaction hiện tại, false chỉ khi trùng mã AN.
     * Lỗi object key hoặc chủ không hợp lệ không được giả thành trùng mã để thử lại.
     * Adapter che dữ liệu nhạy cảm trong log và lỗi; không lưu URL ký.
     */
    boolean insert(DocumentUpload upload);
}
