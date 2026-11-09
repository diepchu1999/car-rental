package com.carrental.storage.application.service;

import com.carrental.storage.api.UploadRef;
import com.carrental.storage.api.StoredImageRef;
import com.carrental.storage.api.SignedImageRef;
import com.carrental.storage.application.port.in.DocumentStorageUseCase;
import com.carrental.storage.application.port.out.DocumentImagePolicyPort;
import com.carrental.storage.application.port.out.DocumentObjectPort;
import com.carrental.storage.application.port.out.InspectDocumentImagePort;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.util.UUID;

/** BR-130: vùng upload và vùng ảnh đã chốt tách biệt, chống thay ảnh bằng URL PUT cũ. */
@Service
class DocumentStorageService implements DocumentStorageUseCase {
    private final DocumentObjectPort objects;
    private final InspectDocumentImagePort images;
    private final DocumentImagePolicyPort policies;
    private final Clock clock;

    /** Nhận cổng thuần, không import MinIO, HTTP hoặc parser ảnh trong application. */
    DocumentStorageService(DocumentObjectPort objects, InspectDocumentImagePort images,
                           DocumentImagePolicyPort policies, Clock clock) {
        this.objects = objects; this.images = images; this.policies = policies; this.clock = clock;
    }

    /** Sinh key không chứa tài khoản/số giấy tờ; identity lưu liên kết tới AN ở phần tiếp theo. */
    @Override public UploadRef createUpload() {
        var now = clock.instant();
        var policy = policies.at(now);
        String key = "incoming/" + UUID.randomUUID();
        return new UploadRef(key, objects.uploadUrl(key, policy.uploadLifetime()), now.plus(policy.uploadLifetime()));
    }

    /** Bytes dùng kiểm cũng là bytes ghi; URL tải cũ chỉ có thể sửa bản tạm. */
    @Override public StoredImageRef sealUpload(String uploadKey) {
        requireKey(uploadKey, "incoming");
        var policy = policies.at(clock.instant());
        byte[] bytes = objects.read(uploadKey, policy.maxBytes());
        var metadata = images.inspect(bytes, policy);
        String key = "sealed/" + UUID.randomUUID();
        objects.put(key, bytes, metadata.contentType());
        return new StoredImageRef(key, metadata.contentType(), bytes.length, metadata.width(), metadata.height());
    }

    /** Không cho xem vùng tạm hoặc dùng key ngoài namespace ảnh giấy tờ. */
    @Override public SignedImageRef createView(String sealedKey) {
        requireKey(sealedKey, "sealed");
        var now = clock.instant();
        var policy = policies.at(now);
        return new SignedImageRef(objects.viewUrl(sealedKey, policy.viewLifetime()), now.plus(policy.viewLifetime()));
    }

    /** Không đưa object key sai vào thông điệp lỗi. */
    private static void requireKey(String key, String prefix) {
        if (key == null || !key.matches(prefix + "/[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) {
            throw new IllegalArgumentException("Invalid document object reference.");
        }
    }
}
