package com.carrental.identity.application.service;

import com.carrental.branch.api.BranchDirectory;
import com.carrental.identity.api.CallerDirectory;
import com.carrental.identity.application.port.in.GetCurrentAccountUseCase;
import com.carrental.identity.application.port.out.ReadAccountPort;
import com.carrental.identity.application.view.AccountDetail;
import com.carrental.identity.application.view.CurrentAccountDetail;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * BR-120/803/809: đọc tài khoản của chính người gọi, không có thao tác tìm người khác theo số điện thoại.
 * ADR-0008: đổi tham chiếu chi nhánh nội bộ thành mã qua branch.api, không join chéo schema.
 * Không đồng bộ tài khoản ở transaction chỉ đọc; bước xác thực đã đồng bộ trước khi gọi use case.
 */
@Service
class AccountQueryService implements GetCurrentAccountUseCase {
    private final CallerDirectory callers;
    private final ReadAccountPort accounts;
    private final BranchDirectory branches;

    /** Nhận các cổng thuần, giữ HTTP/Security/JDBC ngoài application theo R1/R11. */
    AccountQueryService(CallerDirectory callers, ReadAccountPort accounts, BranchDirectory branches) {
        this.callers = callers;
        this.accounts = accounts;
        this.branches = branches;
    }

    /**
     * Xác định người gọi trước mọi tra cứu; không cache danh tính, vai hay thông tin tài khoản trên bean.
     * Nhân viên chưa gán chi nhánh vẫn đọc được /me để lấy mã cho ADMIN gán sau.
     * Bản gán trỏ tới chi nhánh không tồn tại là lỗi nội bộ, không giả thành chưa gán.
     */
    @Override
    @Transactional(readOnly = true)
    public CurrentAccountDetail get() {
        var caller = callers.current();
        var account = accounts.findByCode(caller.accountCode())
                .orElseThrow(() -> DomainException.notFound(ErrorCode.ACCOUNT_NOT_FOUND));
        if (!caller.accountCode().equals(account.code())) {
            throw new IllegalStateException("Account lookup returned a different account.");
        }
        String branchCode = null;
        if (caller.branchId() != null) {
            var branch = branches.findById(caller.branchId())
                    .orElseThrow(() -> new IllegalStateException("Assigned branch could not be resolved."));
            if (branch.id() != caller.branchId() || branch.code() == null || branch.code().isBlank()) {
                throw new IllegalStateException("Branch lookup returned an inconsistent identity.");
            }
            branchCode = branch.code();
        }
        var stored = AccountDetail.from(account);
        return new CurrentAccountDetail(stored.code(), stored.phoneNumber(), caller.roles(),
                branchCode, stored.termsVersion(), stored.consentedAt());
    }
}
