package com.carrental.storage.adapter.in.internal;

import com.carrental.storage.api.DocumentStorageDirectory;
import com.carrental.storage.api.UploadRef;
import com.carrental.storage.api.StoredImageRef;
import com.carrental.storage.api.SignedImageRef;
import com.carrental.storage.application.port.in.DocumentStorageUseCase;
import org.springframework.stereotype.Component;

/** Biên cross-module storage, không mở endpoint tải/xem bỏ qua quyền của identity. */
@Component
class InternalDocumentStorageDirectory implements DocumentStorageDirectory {
    private final DocumentStorageUseCase useCase;
    /** Nhận use case, không gọi SDK trực tiếp. */
    InternalDocumentStorageDirectory(DocumentStorageUseCase useCase) { this.useCase = useCase; }
    /** Chuyển yêu cầu cấp URL tải. */
    @Override public UploadRef createUpload() { return useCase.createUpload(); }
    /** Chuyển yêu cầu kiểm và chốt ảnh. */
    @Override public StoredImageRef sealUpload(String uploadKey) { return useCase.sealUpload(uploadKey); }
    /** Chuyển yêu cầu xem ảnh đã chốt. */
    @Override public SignedImageRef createView(String sealedKey) { return useCase.createView(sealedKey); }
}
