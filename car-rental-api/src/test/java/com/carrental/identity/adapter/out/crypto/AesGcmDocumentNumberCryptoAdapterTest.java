package com.carrental.identity.adapter.out.crypto;

import com.carrental.identity.application.port.out.DocumentNumberCryptoPort;
import com.carrental.identity.config.DocumentCryptoKeys;
import com.carrental.identity.domain.DocumentNumber;
import com.carrental.identity.domain.DocumentProtectionContext;
import com.carrental.identity.domain.DocumentType;
import com.carrental.identity.domain.ProtectedDocumentNumber;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/** BR-130: dùng JCE thật, chống tráo AAD/bản mã, ổn định HMAC và không lẫn trạng thái giữa request. */
class AesGcmDocumentNumberCryptoAdapterTest {
    private static final DocumentNumber NUMBER = new DocumentNumber("001234567890");
    private static final DocumentProtectionContext CONTEXT =
            new DocumentProtectionContext("TK-AAAAAA", DocumentType.IDENTITY_CARD, "GT-AAAAAA");
    private final AesGcmDocumentNumberCryptoAdapter adapter = new AesGcmDocumentNumberCryptoAdapter(keys(1, 1, 2));

    /** Cả CCCD lẫn GPLX đi-về giữ nguyên số 0 đầu, với phong bì không chứa số rõ. */
    @ParameterizedTest
    @EnumSource(DocumentType.class)
    void roundTripsBothDocumentTypes(DocumentType type) {
        var context = new DocumentProtectionContext("TK-AAAAAA", type, "GT-AAAAAA");
        var protectedNumber = adapter.protect(NUMBER, context);
        assertEquals(NUMBER, adapter.reveal(protectedNumber, context));
        assertEquals(1, protectedNumber.keyVersion());
        assertEquals(12, Base64.getDecoder().decode(protectedNumber.nonceBase64()).length);
        assertEquals(NUMBER.value().length() + 16, Base64.getDecoder().decode(protectedNumber.ciphertextBase64()).length);
        assertTrue(protectedNumber.lookupHash().matches("[0-9a-f]{64}"));
        assertFalse(protectedNumber.ciphertextBase64().contains(NUMBER.value()));
    }

    /** Giữ nguyên khoá qua restart vẫn giải được dữ liệu; adapter không âm thầm tạo khoá mới. */
    @Test
    void readsWithANewAdapterUsingTheSameKeys() {
        var protectedNumber = adapter.protect(NUMBER, CONTEXT);
        var restarted = new AesGcmDocumentNumberCryptoAdapter(keys(1, 1, 2));
        assertEquals(NUMBER, restarted.reveal(protectedNumber, CONTEXT));
    }

    /** Cùng một số vẫn có nonce/bản mã khác nhau; hash mới là dữ liệu dùng tìm trùng. */
    @Test
    void usesFreshNonceForEveryEncryption() {
        var first = adapter.protect(NUMBER, CONTEXT);
        var second = adapter.protect(NUMBER, CONTEXT);
        assertNotEquals(first.nonceBase64(), second.nonceBase64());
        assertNotEquals(first.ciphertextBase64(), second.ciphertextBase64());
        assertEquals(first.lookupHash(), second.lookupHash());
    }

    /** BR-130: hai tài khoản nhập cùng CCCD phải có cùng hash, nhưng không đổi chủ bản mã được. */
    @Test
    void findsSameNumberAcrossAccountsWithoutSharingCiphertextContext() {
        var other = new DocumentProtectionContext("TK-BBBBBB", DocumentType.IDENTITY_CARD, "GT-BBBBBB");
        var first = adapter.protect(NUMBER, CONTEXT);
        var second = adapter.protect(new DocumentNumber(" 001234567890 "), other);
        assertEquals(first.lookupHash(), second.lookupHash());
        assertEquals(NUMBER, adapter.reveal(second, other));
        assertSafeFailure(first, other);
    }

    /** Hai loại giấy tờ hoặc hai số khác nhau không dùng chung khoá tra cứu. */
    @Test
    void separatesHashesByTypeAndNumber() {
        String original = adapter.protect(NUMBER, CONTEXT).lookupHash();
        var license = new DocumentProtectionContext("TK-AAAAAA", DocumentType.DRIVER_LICENSE, "GT-AAAAAA");
        assertNotEquals(original, adapter.protect(NUMBER, license).lookupHash());
        assertNotEquals(original, adapter.protect(new DocumentNumber("001234567891"), CONTEXT).lookupHash());
        assertNotEquals(original, adapter.protect(new DocumentNumber("1234567890"), CONTEXT).lookupHash());
    }

    /** Không tráo phong bì sang tài khoản, loại giấy tờ hoặc bản nộp khác dù khoá giải mã đúng. */
    @Test
    void rejectsEveryChangedContextField() {
        var protectedNumber = adapter.protect(NUMBER, CONTEXT);
        assertSafeFailure(protectedNumber, new DocumentProtectionContext("TK-BBBBBB", DocumentType.IDENTITY_CARD, "GT-AAAAAA"));
        assertSafeFailure(protectedNumber, new DocumentProtectionContext("TK-AAAAAA", DocumentType.DRIVER_LICENSE, "GT-AAAAAA"));
        assertSafeFailure(protectedNumber, new DocumentProtectionContext("TK-AAAAAA", DocumentType.IDENTITY_CARD, "GT-BBBBBB"));
    }

    /** Chạm một bit ở bản mã, tag hoặc nonce đều bị xác thực từ chối, không trả số giấy tờ sai. */
    @Test
    void rejectsCiphertextTagAndNonceTampering() {
        var value = adapter.protect(NUMBER, CONTEXT);
        assertSafeFailure(new ProtectedDocumentNumber(1, value.nonceBase64(), flip(value.ciphertextBase64(), 0), value.lookupHash()), CONTEXT);
        int last = Base64.getDecoder().decode(value.ciphertextBase64()).length - 1;
        assertSafeFailure(new ProtectedDocumentNumber(1, value.nonceBase64(), flip(value.ciphertextBase64(), last), value.lookupHash()), CONTEXT);
        assertSafeFailure(new ProtectedDocumentNumber(1, flip(value.nonceBase64(), 0), value.ciphertextBase64(), value.lookupHash()), CONTEXT);
    }

    /** Hash cũng thuộc AAD: không sửa hash để né đối chiếu trùng hoặc gán phong bì cho số khác. */
    @Test
    void rejectsLookupHashTampering() {
        var value = adapter.protect(NUMBER, CONTEXT);
        String replacement = (value.lookupHash().startsWith("0") ? "1" : "0") + value.lookupHash().substring(1);
        assertSafeFailure(new ProtectedDocumentNumber(1, value.nonceBase64(), value.ciphertextBase64(), replacement), CONTEXT);
    }

    /** Không thử đại khoá hiện tại khi phiên bản lưu trên bản ghi không khớp cấu hình. */
    @Test
    void rejectsUnknownKeyVersion() {
        var value = adapter.protect(NUMBER, CONTEXT);
        assertSafeFailure(new ProtectedDocumentNumber(2, value.nonceBase64(), value.ciphertextBase64(), value.lookupHash()), CONTEXT);
    }

    /** Thay riêng khoá AES hoặc HMAC đều không thể đọc phong bì cũ như thể cấu hình còn đúng. */
    @Test
    void rejectsWrongEncryptionOrHashKey() {
        var value = adapter.protect(NUMBER, CONTEXT);
        assertThrows(IllegalStateException.class,
                () -> new AesGcmDocumentNumberCryptoAdapter(keys(1, 3, 2)).reveal(value, CONTEXT));
        assertThrows(IllegalStateException.class,
                () -> new AesGcmDocumentNumberCryptoAdapter(keys(1, 1, 3)).reveal(value, CONTEXT));
    }

    /** Dữ liệu Base64 hỏng không được lọt message decoder hoặc dữ liệu đầu vào vào stack trace. */
    @ParameterizedTest
    @ValueSource(strings = {"secret-sentinel!", "AAAA", "AA==", "AAECAwQFBgcICQo="})
    void rejectsMalformedOrTruncatedEnvelope(String invalid) {
        var value = adapter.protect(NUMBER, CONTEXT);
        assertSafeFailure(new ProtectedDocumentNumber(1, invalid, value.ciphertextBase64(), value.lookupHash()), CONTEXT);
        assertSafeFailure(new ProtectedDocumentNumber(1, value.nonceBase64(), invalid, value.lookupHash()), CONTEXT);
        assertSafeFailure(new ProtectedDocumentNumber(1, value.nonceBase64(), value.ciphertextBase64(), invalid), CONTEXT);
    }

    /** Một bean crypto phục vụ đồng thời nhiều request không lẫn Cipher/Mac hoặc sinh lại cùng nonce. */
    @Test
    void handlesParallelCallsWithoutSharedCipherState() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var operations = IntStream.range(0, 100).<Callable<ProtectedDocumentNumber>>mapToObj(index -> () -> {
                var number = new DocumentNumber("00000" + index);
                var value = adapter.protect(number, CONTEXT);
                assertEquals(number, adapter.reveal(value, CONTEXT));
                return value;
            }).toList();
            var futures = executor.invokeAll(operations, 10, TimeUnit.SECONDS);
            var nonces = new HashSet<String>();
            var hashes = new HashSet<String>();
            for (var future : futures) {
                assertFalse(future.isCancelled(), "Concurrent crypto operation timed out.");
                var value = future.get();
                assertTrue(nonces.add(value.nonceBase64()), "Nonce was reused.");
                assertTrue(hashes.add(value.lookupHash()), "Different numbers shared a lookup hash.");
            }
        }
    }

    /** Spring lắp được adapter package-private qua port, không cần làm implementation public. */
    @Test
    void exposesOnlyPortForApplicationWiring() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(DocumentCryptoKeys.class, () -> keys(1, 1, 2));
            context.register(AesGcmDocumentNumberCryptoAdapter.class);
            context.refresh();
            var port = context.getBean(DocumentNumberCryptoPort.class);
            assertEquals(NUMBER, port.reveal(port.protect(NUMBER, CONTEXT), CONTEXT));
        }
    }

    /** In toàn stack để kiểm không giữ số rõ, hash, bản mã, nonce hay cause/suppressed của provider. */
    private void assertSafeFailure(ProtectedDocumentNumber value, DocumentProtectionContext context) {
        var failure = assertThrows(IllegalStateException.class, () -> adapter.reveal(value, context));
        assertEquals("Document number cryptographic operation failed.", failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
        assertTrue(failure.getStackTrace().length > 0);
        var output = new StringWriter();
        failure.printStackTrace(new PrintWriter(output));
        String trace = output.toString();
        assertFalse(trace.contains(NUMBER.value()));
        assertFalse(trace.contains(value.ciphertextBase64()));
        assertFalse(trace.contains(value.nonceBase64()));
        assertFalse(trace.contains(value.lookupHash()));
        assertFalse(trace.contains("secret-sentinel"));
    }

    /** Thay đúng một bit mà vẫn giữ Base64 hợp lệ để chứng minh lỗi đến từ xác thực GCM. */
    private static String flip(String encoded, int position) {
        byte[] bytes = Base64.getDecoder().decode(encoded);
        bytes[position] ^= 1;
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** Khoá giả tất định để kiểm hồi quy; tuyệt đối không dùng làm khoá local/production. */
    private static DocumentCryptoKeys keys(int version, int encryptionValue, int hashValue) {
        byte[] encryption = new byte[32];
        byte[] hashing = new byte[32];
        Arrays.fill(encryption, (byte) encryptionValue);
        Arrays.fill(hashing, (byte) hashValue);
        return new DocumentCryptoKeys(version, encryption, hashing);
    }
}
