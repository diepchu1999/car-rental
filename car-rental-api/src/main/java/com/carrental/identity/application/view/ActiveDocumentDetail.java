package com.carrental.identity.application.view;

import com.carrental.identity.domain.DocumentType;
import java.time.LocalDate;

/**
 * BR-106/106b/128/130: metadata tối thiểu của bản VERIFIED đang được head chọn để kiểm điều kiện thuê.
 * Không đồng nghĩa còn hiệu lực: application phải so expiresOn với ngày kết thúc chuyến.
 * licenseClass, automaticOnly và issuedOn chỉ dành cho GPLX; issuedOn có thể null ở bản lịch sử.
 * Không chứa số/ảnh/nhân thân; cổng thuê không được đoán ngày cấp khi metadata còn thiếu.
 */
public record ActiveDocumentDetail(String code, DocumentType documentType, LocalDate expiresOn,
        String licenseClass, Boolean automaticOnly, LocalDate issuedOn) {
    /** Không để metadata giấy tờ lọt ra log qua toString mặc định của record. */
    @Override public String toString() { return "ActiveDocumentDetail[redacted]"; }
}
