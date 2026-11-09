package com.carrental.identity.domain;

import java.time.LocalDate;
import java.util.Objects;

/** BR-128: một bộ cấu hình bất biến chứa mốc ngày cấp và hai bảng hạng độc lập. */
public record LicenseClassPolicy(LocalDate cutoverDate, LicenseClassTable beforeCutover,
        LicenseClassTable fromCutover) {
    /** Không nhận chính sách thiếu mốc hoặc thiếu một bảng; không tự hợp nhất hay điền bảng còn lại. */
    public LicenseClassPolicy {
        Objects.requireNonNull(cutoverDate, "cutoverDate");
        Objects.requireNonNull(beforeCutover, "beforeCutover");
        Objects.requireNonNull(fromCutover, "fromCutover");
    }

    /** Ngày cấp đúng mốc thuộc bảng mới; chỉ chọn theo dữ liệu, không đóng cứng ngày hoặc tên hạng. */
    public LicenseClassTable tableFor(LocalDate issuedOn) {
        Objects.requireNonNull(issuedOn, "issuedOn");
        return issuedOn.isBefore(cutoverDate) ? beforeCutover : fromCutover;
    }
}
