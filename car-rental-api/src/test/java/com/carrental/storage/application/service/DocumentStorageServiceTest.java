package com.carrental.storage.application.service;

import com.carrental.storage.application.port.out.*;
import com.carrental.storage.domain.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** BR-130: kiểm chốt ảnh không copy lại object tạm và không phát URL xem vùng chưa kiểm. */
class DocumentStorageServiceTest {
    private final DocumentObjectPort objects = mock(DocumentObjectPort.class);
    private final InspectDocumentImagePort inspector = mock(InspectDocumentImagePort.class);
    private final DocumentImagePolicyPort policies = mock(DocumentImagePolicyPort.class);
    private final Instant now = Instant.parse("2026-10-22T00:00:00Z");
    private final DocumentImagePolicy policy = new DocumentImagePolicy(5000000, 40000000,
            Duration.ofMinutes(5), Duration.ofSeconds(60));

    /** Lắp dependency giả chỉ ở unit test, integration test dùng SDK/MinIO thật. */
    private DocumentStorageService service() {
        when(policies.at(now)).thenReturn(policy);
        return new DocumentStorageService(objects, inspector, policies, Clock.fixed(now, ZoneOffset.UTC));
    }

    /** Hai lượt tải khác key; URL và key không xuất hiện trong toString. */
    @Test void allocatesIndependentUploadsWithConfiguredExpiry() {
        var service = service();
        when(objects.uploadUrl(anyString(), eq(policy.uploadLifetime()))).thenReturn("https://secret.invalid/upload");
        var first = service.createUpload();
        var second = service.createUpload();
        assertNotEquals(first.objectKey(), second.objectKey());
        assertTrue(first.objectKey().startsWith("incoming/"));
        assertEquals(now.plusSeconds(300), first.expiresAt());
        assertEquals("UploadRef[redacted]", first.toString());
    }

    /** Ghi chính mảng đã được parser kiểm, không đọc lần hai sau kiểm tra. */
    @Test void sealsExactlyTheValidatedBytesUnderANewKey() {
        String key = "incoming/00000000-0000-0000-0000-000000000001";
        byte[] content = new byte[]{1, 2, 3};
        var service = service();
        when(objects.read(key, policy.maxBytes())).thenReturn(content);
        when(inspector.inspect(content, policy)).thenReturn(new ImageMetadata("image/png", 2, 3));
        var stored = service.sealUpload(key);
        var written = ArgumentCaptor.forClass(byte[].class);
        verify(objects).put(eq(stored.objectKey()), written.capture(), eq("image/png"));
        assertSame(content, written.getValue());
        assertTrue(stored.objectKey().startsWith("sealed/"));
        assertEquals(3, stored.sizeBytes());
        assertEquals("StoredImageRef[redacted]", stored.toString());
        verify(objects, times(1)).read(anyString(), anyLong());
    }

    /** Ảnh không hợp lệ không sinh object đã chốt. */
    @Test void invalidImageIsNeverStored() {
        var service = service();
        String key = "incoming/00000000-0000-0000-0000-000000000001";
        byte[] content = new byte[]{1};
        when(objects.read(key, policy.maxBytes())).thenReturn(content);
        when(inspector.inspect(content, policy)).thenThrow(new IllegalArgumentException("Invalid image."));
        assertThrows(IllegalArgumentException.class, () -> service.sealUpload(key));
        verify(objects, never()).put(anyString(), any(), anyString());
    }

    /** Không biến storage thành cổng ký key bất kỳ, hoặc xem file tạm. */
    @Test void rejectsWrongObjectNamespacesWithoutCallingStorage() {
        var service = service();
        assertThrows(IllegalArgumentException.class, () -> service.createView("incoming/00000000-0000-0000-0000-000000000001"));
        assertThrows(IllegalArgumentException.class, () -> service.sealUpload("../secret"));
        verifyNoInteractions(objects);
    }

    /** Hạn xem 60 giây đọc từ cấu hình, record không lộ chữ ký. */
    @Test void usesConfiguredViewLifetime() {
        var service = service();
        String key = "sealed/00000000-0000-0000-0000-000000000001";
        when(objects.viewUrl(key, policy.viewLifetime())).thenReturn("https://secret.invalid/view");
        var result = service.createView(key);
        assertEquals(now.plusSeconds(60), result.expiresAt());
        assertEquals("SignedImageRef[redacted]", result.toString());
    }
}
