package com.carrental.identity.config;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;

/** Cặp khoá độc lập cho AES-256-GCM và HMAC-SHA-256; không tự sinh khoá mới mỗi lần khởi động. */
public final class DocumentCryptoKeys {
    private final int version;
    private final SecretKey encryptionKey;
    private final SecretKey hashKey;

    /** Sao chép bytes vào key spec; chặn khoá sai cỡ hoặc vô tình dùng chung khoá cho hai mục đích. */
    public DocumentCryptoKeys(int version, byte[] encryptionKey, byte[] hashKey) {
        if (version <= 0 || encryptionKey == null || encryptionKey.length != 32
                || hashKey == null || hashKey.length != 32 || MessageDigest.isEqual(encryptionKey, hashKey)) {
            throw new IllegalArgumentException("Invalid document encryption/hash key configuration.");
        }
        this.version = version;
        this.encryptionKey = new SecretKeySpec(encryptionKey, "AES");
        this.hashKey = new SecretKeySpec(hashKey, "HmacSHA256");
    }

    /** Phiên bản được ghi cùng bản mã; không cho adapter đoán khoá khi đọc phiên bản khác. */
    public int version() {
        return version;
    }

    /** Khoá mã hoá chỉ dành cho adapter JCE trong module identity. */
    public SecretKey encryptionKey() {
        return encryptionKey;
    }

    /** Khoá HMAC độc lập giúp người chỉ đọc được CSDL không dò số giấy tờ bằng hash thường. */
    public SecretKey hashKey() {
        return hashKey;
    }

    /** Không phản chiếu khoá khi cấu hình bị đưa vào log. */
    @Override
    public String toString() {
        return "DocumentCryptoKeys[redacted]";
    }
}
