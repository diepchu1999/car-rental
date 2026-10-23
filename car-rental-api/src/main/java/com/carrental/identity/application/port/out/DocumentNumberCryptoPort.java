package com.carrental.identity.application.port.out;

import com.carrental.identity.domain.DocumentNumber;
import com.carrental.identity.domain.DocumentProtectionContext;
import com.carrental.identity.domain.ProtectedDocumentNumber;

/** Cổng bảo vệ số CCCD/GPLX theo BR-130, tách nghiệp vụ khỏi JCE và nguồn khoá cấu hình. */
public interface DocumentNumberCryptoPort {
    /** Mã hoá với nonce mới và tạo hash tra cứu; không ghi số rõ vào kho lưu trữ hay log. */
    ProtectedDocumentNumber protect(DocumentNumber number, DocumentProtectionContext context);

    /**
     * Xác thực phong bì rồi giải mã; caller phải kiểm quyền và audit trước khi cung cấp dữ liệu cho người khác.
     * Sai khoá, ngữ cảnh hoặc bản mã là lỗi nội bộ an toàn, không phải một số giấy tờ rỗng hay lỗi 404.
     */
    DocumentNumber reveal(ProtectedDocumentNumber number, DocumentProtectionContext context);
}
