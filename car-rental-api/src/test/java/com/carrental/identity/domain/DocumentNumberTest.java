package com.carrental.identity.domain;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** BR-130: chuẩn hoá tối thiểu số giấy tờ, không phản chiếu dữ liệu nhạy cảm vào lỗi/toString. */
class DocumentNumberTest {
    /** Giữ số 0 đầu và nội dung bên trong; chỉ bỏ khoảng trắng hai đầu theo hợp đồng của value object. */
    @Test
    void stripsOnlyOuterWhitespaceAndPreservesLeadingZeros() {
        assertEquals("001234567890", new DocumentNumber("  001234567890  ").value());
        assertEquals("Ab-01 02", new DocumentNumber(" Ab-01 02 ").value());
    }

    /** Không tự đoán độ dài hay mẫu số CCCD/GPLX khi BR-130 chưa quy định. */
    @Test
    void doesNotInventDocumentNumberLengthOrCharacterRules() {
        assertEquals("0", new DocumentNumber("0").value());
        assertEquals("12345678901234567890", new DocumentNumber("12345678901234567890").value());
    }

    /** Trường rỗng, ký tự điều khiển và surrogate không hợp lệ đều ra INVALID_REQUEST không kèm số. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\u2003", "fake-number\n", "\rfake-number", "fake\tvalue", "fake\u0000value", "\uD800"})
    void rejectsMissingOrUnsafeTextWithoutEchoingIt(String input) {
        var error = assertThrows(DomainException.class, () -> new DocumentNumber(input));
        assertEquals(ErrorCode.INVALID_REQUEST, error.errorCode());
        assertFalse(error.toString().contains("fake"));
        assertNull(error.getCause());
    }

    /** Cả số rõ và phong bì đều không dùng toString sinh tự động chứa dữ liệu nhạy cảm. */
    @Test
    void redactsPlaintextAndProtectedEnvelope() {
        assertEquals("DocumentNumber[redacted]", new DocumentNumber("001234567890").toString());
        assertEquals("ProtectedDocumentNumber[redacted]",
                new ProtectedDocumentNumber(1, "private-nonce", "private-ciphertext", "private-hash").toString());
    }

    /** Ngữ cảnh chỉ nhận mã nghiệp vụ và loại giấy tờ; không chấp nhận trường có dấu phân cách AAD. */
    @Test
    void validatesProtectionContextWithoutEchoingRejectedValues() {
        assertDoesNotThrow(() -> new DocumentProtectionContext("TK-ABC123", DocumentType.IDENTITY_CARD, "GT-ABC123"));
        var invalid = assertThrows(IllegalArgumentException.class,
                () -> new DocumentProtectionContext("TK-ABC123\nsecret-value", DocumentType.IDENTITY_CARD, "GT-ABC123"));
        assertFalse(invalid.toString().contains("secret-value"));
        assertThrows(IllegalArgumentException.class,
                () -> new DocumentProtectionContext("TK-ABC123", null, "GT-ABC123"));
        assertThrows(IllegalArgumentException.class,
                () -> new DocumentProtectionContext("TK-ABC123", DocumentType.IDENTITY_CARD, "AN-ABC123"));
    }

    /** Phong bì thiếu thành phần hoặc phiên bản không dương không được đi tiếp tới adapter. */
    @Test
    void rejectsIncompleteEnvelope() {
        assertThrows(IllegalArgumentException.class, () -> new ProtectedDocumentNumber(0, "n", "c", "h"));
        assertThrows(IllegalArgumentException.class, () -> new ProtectedDocumentNumber(1, null, "c", "h"));
        assertThrows(IllegalArgumentException.class, () -> new ProtectedDocumentNumber(1, "n", "", "h"));
        assertThrows(IllegalArgumentException.class, () -> new ProtectedDocumentNumber(1, "n", "c", " "));
    }
}
