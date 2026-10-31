package com.carrental.identity.adapter.in.internal;

import com.carrental.identity.api.RentalEligibilityDirectory;
import com.carrental.identity.application.port.in.CheckRentalEligibilityUseCase;
import com.carrental.identity.application.query.CheckRentalEligibilityQuery;
import org.springframework.stereotype.Component;
import java.time.Instant;

/** ADR-0004/0008: nối API nội bộ vào use case, không chứa quy tắc giấy tờ hoặc truy cập dữ liệu. */
@Component
class RentalEligibilityDirectoryAdapter implements RentalEligibilityDirectory {
    private final CheckRentalEligibilityUseCase eligibility;

    /** Nhận cổng đầu vào thay vì phụ thuộc implementation application. */
    RentalEligibilityDirectoryAdapter(CheckRentalEligibilityUseCase eligibility) { this.eligibility = eligibility; }

    /** Giữ nguyên dữ liệu từ bên gọi và lỗi của use case; không đổi thành boolean mất nguyên nhân. */
    @Override
    public void requireSelfDriveEligible(String accountCode, int seats, String transmission, Instant tripEnd) {
        eligibility.check(CheckRentalEligibilityQuery.from(accountCode, seats, transmission, tripEnd));
    }
}
