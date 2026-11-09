package com.carrental.storage.application.port.in;

import com.carrental.storage.api.UploadRef;
import com.carrental.storage.api.StoredImageRef;
import com.carrental.storage.api.SignedImageRef;

/** Use case nội bộ lưu ảnh BR-130; không công khai HTTP và không tự xác định chủ hồ sơ. */
public interface DocumentStorageUseCase {
    /** Cấp lượt tải riêng biệt. */
    UploadRef createUpload();
    /** Chốt bytes đã kiểm vào tên object độc lập với URL upload. */
    StoredImageRef sealUpload(String uploadKey);
    /** Cấp quyền xem tạm thời cho key đã chốt. */
    SignedImageRef createView(String sealedKey);
}
