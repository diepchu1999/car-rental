package com.carrental.storage.application.port.out;

import com.carrental.storage.domain.DocumentImagePolicy;
import com.carrental.storage.domain.ImageMetadata;

/** Tách thư viện đọc ảnh khỏi application/domain theo Hexagonal. */
public interface InspectDocumentImagePort {
    /** Kiểm nội dung và kích thước trước giải nén; dữ liệu sai ném lỗi BR-130 đã chuẩn hoá. */
    ImageMetadata inspect(byte[] bytes, DocumentImagePolicy policy);
}
