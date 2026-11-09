package com.carrental.storage.adapter.out.storage;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import okhttp3.Headers;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** BR-130, security §3: kiểm giới hạn GET độc lập và không để exception SDK làm lộ dữ liệu. */
class MinioDocumentObjectAdapterTest {
    private final MinioClient client = mock(MinioClient.class);
    private final MinioDocumentObjectAdapter adapter = new MinioDocumentObjectAdapter(client, "documents-test");

    /** Object bị thay sau HEAD vẫn bị chặn khi GET vượt giới hạn, không đọc vô hạn. */
    @Test void rejectsGrowthBetweenHeadAndGet() throws Exception {
        var stat = mock(StatObjectResponse.class);
        when(stat.size()).thenReturn(2L);
        when(client.statObject(any(StatObjectArgs.class))).thenReturn(stat);
        var stream = spy(new ByteArrayInputStream(new byte[20]));
        when(client.getObject(any(GetObjectArgs.class))).thenReturn(new GetObjectResponse(
                new Headers.Builder().build(), "documents-test", "us-east-1", "incoming/test", stream));
        var failure = assertThrows(DomainException.class, () -> adapter.read("incoming/test", 5));
        assertEquals(ErrorCode.DOCUMENT_IMAGE_TOO_LARGE, failure.errorCode());
        assertEquals(14, stream.available());
        verify(stream).close();
    }

    /** HEAD quá lớn không cần tải dữ liệu; kiểm đúng mã lỗi nghiệp vụ. */
    @Test void oversizedHeadSkipsDownload() throws Exception {
        var stat = mock(StatObjectResponse.class);
        when(stat.size()).thenReturn(6000000L);
        when(client.statObject(any(StatObjectArgs.class))).thenReturn(stat);
        assertEquals(ErrorCode.DOCUMENT_IMAGE_TOO_LARGE,
                assertThrows(DomainException.class, () -> adapter.read("incoming/test", 5000000)).errorCode());
        verify(client, never()).getObject(any(GetObjectArgs.class));
    }

    /** Chỉ object không tồn tại được ánh xạ thành chưa upload; không che lỗi cấu hình bucket/quyền. */
    @Test void mapsOnlyMissingObjectToUploadNotReady() throws Exception {
        doThrow(sdkFailure("NoSuchKey", 404)).when(client).statObject(any(StatObjectArgs.class));
        assertEquals(ErrorCode.DOCUMENT_UPLOAD_NOT_READY,
                assertThrows(DomainException.class, () -> adapter.read("incoming/test", 5000000)).errorCode());
        for (String code : new String[]{"AccessDenied", "NoSuchBucket"}) {
            var original = sdkFailure(code, code.equals("AccessDenied") ? 403 : 404);
            doThrow(original).when(client).statObject(any(StatObjectArgs.class));
            var sanitized = assertThrows(IllegalStateException.class, () -> adapter.read("incoming/test", 5000000));
            assertArrayEquals(original.getStackTrace(), sanitized.getStackTrace());
            assertSafe(sanitized, "ErrorResponseException");
        }
    }

    /** Exception SDK thật có stack trace thật; mọi giá trị nhạy cảm trong fixture đều là chuỗi giả. */
    private static ErrorResponseException sdkFailure(String code, int status) {
        var error = new ErrorResponse(code, "secret-sentinel", "documents-test", "incoming/private-key",
                "/documents-test/incoming/private-key", "test-request", "test-host");
        var request = new Request.Builder().url("https://signed.invalid/incoming/private-key?token=private").build();
        var response = new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(status).message("Test storage failure").build();
        return new ErrorResponseException(error, response, "secret-sentinel HTTP trace");
    }

    /** Cả ký URL, GET và PUT đều loại message/cause/suppressed nhạy cảm trước khi lên tầng log. */
    @Test void sanitizesAllSdkFailurePathsIncludingStackTrace() throws Exception {
        var sensitive = new IllegalArgumentException("secret-sentinel incoming/private-key https://signed.invalid/?token=private",
                new IllegalStateException("secret-sentinel nested"));
        sensitive.addSuppressed(new IllegalStateException("secret-sentinel suppressed"));
        when(client.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class))).thenThrow(sensitive);
        when(client.statObject(any(StatObjectArgs.class))).thenThrow(sensitive);
        when(client.putObject(any(PutObjectArgs.class))).thenThrow(sensitive);
        assertSafe(assertThrows(IllegalStateException.class, () -> adapter.uploadUrl("incoming/test", Duration.ofMinutes(5))));
        assertSafe(assertThrows(IllegalStateException.class, () -> adapter.viewUrl("sealed/test", Duration.ofSeconds(60))));
        assertSafe(assertThrows(IllegalStateException.class, () -> adapter.read("incoming/test", 5000000)));
        assertSafe(assertThrows(IllegalStateException.class, () -> adapter.put("sealed/test", new byte[]{1}, "image/png")));
    }

    /** Kiểm đầu ra printStackTrace giống nội dung logger sẽ ghi, không chỉ getMessage. */
    private static void assertSafe(IllegalStateException failure) {
        assertSafe(failure, "IllegalArgumentException");
    }

    /** Cùng kiểm chứng che dữ liệu cho cả exception Java và exception SDK thật. */
    private static void assertSafe(IllegalStateException failure, String expectedType) {
        var output = new StringWriter();
        failure.printStackTrace(new PrintWriter(output));
        assertTrue(failure.getMessage().contains(expectedType));
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
        for (String forbidden : new String[]{"secret-sentinel", "private-key", "signed.invalid", "token=private"})
            assertFalse(output.toString().contains(forbidden));
    }
}
