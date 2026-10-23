package com.carrental.identity.domain;

/**
 * Phong bì bảo vệ số giấy tờ theo BR-130; vẫn là dữ liệu nhạy cảm, tuyệt đối không ghi vào log.
 * Phiên bản nhận diện cặp khoá mã hoá/băm; chưa có cơ chế xoay vòng hoặc tự đọc khoá phiên bản cũ.
 *
 * @param keyVersion phiên bản cặp khoá dùng để tạo phong bì
 * @param nonceBase64 nonce ngẫu nhiên của riêng lần mã hoá
 * @param ciphertextBase64 bản mã kèm authentication tag
 * @param lookupHash HMAC phục vụ tìm trùng trong cùng loại giấy tờ, không phải hash công khai
 */
public record ProtectedDocumentNumber(int keyVersion, String nonceBase64, String ciphertextBase64,
                                      String lookupHash) {
    /** Kiểm cấu trúc bắt buộc; adapter chịu trách nhiệm kiểm định dạng mật mã và authentication tag. */
    public ProtectedDocumentNumber {
        if (keyVersion <= 0 || nonceBase64 == null || nonceBase64.isBlank()
                || ciphertextBase64 == null || ciphertextBase64.isBlank()
                || lookupHash == null || lookupHash.isBlank()) {
            throw new IllegalArgumentException("Invalid protected document number.");
        }
    }

    /** Không để nonce, bản mã hay hash lọt vào log qua toString mặc định của record. */
    @Override
    public String toString() {
        return "ProtectedDocumentNumber[redacted]";
    }
}
