package com.carrental.identity.adapter.out.crypto;

import com.carrental.identity.application.port.out.DocumentNumberCryptoPort;
import com.carrental.identity.config.DocumentCryptoKeys;
import com.carrental.identity.domain.DocumentNumber;
import com.carrental.identity.domain.DocumentProtectionContext;
import com.carrental.identity.domain.DocumentType;
import com.carrental.identity.domain.ProtectedDocumentNumber;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;

/**
 * BR-130/security-guideline §3: AES-256-GCM với nonce 96 bit và tag 128 bit; HMAC-SHA-256 để tìm trùng.
 * Không tự viết thuật toán mật mã. Mỗi lời gọi tạo Cipher/Mac riêng để không chia sẻ trạng thái giữa request.
 * Hash phân vùng theo loại giấy tờ nhưng không theo tài khoản: hai tài khoản trùng CCCD phải tìm ra nhau.
 */
@Component
class AesGcmDocumentNumberCryptoAdapter implements DocumentNumberCryptoPort {
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final DocumentCryptoKeys keys;
    private final SecureRandom random = new SecureRandom();

    /** Nhận cặp khoá đã được cấu hình kiểm tra; không đọc .env hoặc tạo khoá tại adapter. */
    AesGcmDocumentNumberCryptoAdapter(DocumentCryptoKeys keys) {
        this.keys = Objects.requireNonNull(keys, "Document crypto keys are required.");
    }

    /** Mỗi lần nộp có nonce mới; hash giữ ổn định với cùng loại và số đã chuẩn hoá. */
    @Override
    public ProtectedDocumentNumber protect(DocumentNumber number, DocumentProtectionContext context) {
        Objects.requireNonNull(number, "Document number is required.");
        Objects.requireNonNull(context, "Document protection context is required.");
        byte[] plaintext = number.value().getBytes(StandardCharsets.UTF_8);
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            String hash = fingerprint(context.documentType(), plaintext);
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, nonce);
            cipher.updateAAD(aad(context, keys.version(), hash));
            return new ProtectedDocumentNumber(keys.version(), Base64.getEncoder().encodeToString(nonce),
                    Base64.getEncoder().encodeToString(cipher.doFinal(plaintext)), hash);
        } catch (GeneralSecurityException | RuntimeException failure) {
            throw safeFailure(failure);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    /**
     * Xác thực cả bản mã, context và hash; không thử khoá khác hay trả số giả khi dữ liệu hỏng.
     * Xoá buffer bytes tạm sau dùng; String trong JVM không thể được xoá chủ động một cách bảo đảm.
     */
    @Override
    public DocumentNumber reveal(ProtectedDocumentNumber number, DocumentProtectionContext context) {
        Objects.requireNonNull(number, "Protected document number is required.");
        Objects.requireNonNull(context, "Document protection context is required.");
        byte[] plaintext = null;
        try {
            if (number.keyVersion() != keys.version() || !number.lookupHash().matches("[0-9a-f]{64}")) {
                throw new GeneralSecurityException();
            }
            byte[] nonce = Base64.getDecoder().decode(number.nonceBase64());
            byte[] ciphertext = Base64.getDecoder().decode(number.ciphertextBase64());
            if (nonce.length != NONCE_BYTES || ciphertext.length <= TAG_BITS / 8) {
                throw new GeneralSecurityException();
            }
            Cipher cipher = cipher(Cipher.DECRYPT_MODE, nonce);
            cipher.updateAAD(aad(context, number.keyVersion(), number.lookupHash()));
            plaintext = cipher.doFinal(ciphertext);
            byte[] expectedHash = HexFormat.of().parseHex(fingerprint(context.documentType(), plaintext));
            if (!MessageDigest.isEqual(expectedHash, HexFormat.of().parseHex(number.lookupHash()))) {
                throw new GeneralSecurityException();
            }
            return new DocumentNumber(new String(plaintext, StandardCharsets.UTF_8));
        } catch (GeneralSecurityException | RuntimeException failure) {
            throw safeFailure(failure);
        } finally {
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
        }
    }

    /** Tạo JCE Cipher riêng cho một thao tác, không dùng đối tượng có trạng thái dùng chung. */
    private Cipher cipher(int mode, byte[] nonce) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, keys.encryptionKey(), new GCMParameterSpec(TAG_BITS, nonce));
        return cipher;
    }

    /**
     * HMAC có phân vùng ứng dụng/loại, giữ số 0 đầu; không trộn chủ sở hữu vào hash tra cứu trùng CCCD.
     * Phiên bản cặp khoá được lưu riêng trong phong bì, việc chuyển hash sang khoá mới phải có migration riêng.
     */
    private String fingerprint(DocumentType type, byte[] plaintext) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(keys.hashKey());
        mac.update(("car-rental/document-number/hash/v1\n" + type.name() + "\n")
                .getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(mac.doFinal(plaintext));
    }

    /**
     * AAD có phiên bản định dạng, phiên bản khoá, tài khoản, loại, mã bản nộp và hash để chống tráo cột.
     * Các trường đã giới hạn bảng chữ cái, không chứa dấu phân cách xuống dòng nên mã hoá không nhập nhằng.
     */
    private static byte[] aad(DocumentProtectionContext context, int version, String hash) {
        return String.join("\n", "car-rental/document-number/aad/v1", Integer.toString(version),
                context.accountCode(), context.documentType().name(), context.documentCode(), hash)
                .getBytes(StandardCharsets.UTF_8);
    }

    /** Giữ vị trí lỗi nhưng bỏ message/cause/suppressed của provider có thể chứa dữ liệu mật mã. */
    private static IllegalStateException safeFailure(Exception original) {
        var safe = new IllegalStateException("Document number cryptographic operation failed.");
        safe.setStackTrace(original.getStackTrace());
        return safe;
    }
}
