package com.carrental.identity.application.service;

import com.carrental.identity.application.command.ResolveAccountCommand;
import com.carrental.identity.application.port.in.ResolveAccountUseCase;
import com.carrental.identity.application.port.out.ReadAccountPort;
import com.carrental.identity.application.port.out.WriteAccountPort;
import com.carrental.identity.application.view.AccountDetail;
import com.carrental.identity.domain.Account;
import com.carrental.shared.code.BusinessCodeGenerator;
import com.carrental.shared.validation.Validations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * BR-120/809: đồng bộ danh tính nhưng không tự gán vai, đổi số hay thay bằng chứng đã lưu.
 * Ghi và đọc lại trong một transaction; READ_COMMITTED cho phép thấy bên thắng đã commit
 * sau khi INSERT hoặc UPDATE của mình chờ UNIQUE/khóa dòng. Không giữ transaction xuyên HTTP.
 */
@Service
class AccountCommandService implements ResolveAccountUseCase {
    /** Giới hạn kỹ thuật chống sinh mã lặp vô hạn, tính cả lần chèn đầu tiên. */
    private static final int MAX_CODE_GENERATION_ATTEMPTS = 5;
    private final WriteAccountPort write;
    private final ReadAccountPort read;
    private final BusinessCodeGenerator codes;
    private final Clock clock;

    /** Nhận cổng lưu trữ, bộ sinh mã và đồng hồ dùng chung, không phụ thuộc JDBC hay HTTP. */
    AccountCommandService(WriteAccountPort write, ReadAccountPort read, BusinessCodeGenerator codes, Clock clock) {
        this.write = write;
        this.read = read;
        this.codes = codes;
        this.clock = clock;
    }

    /**
     * Đọc subject trước để request lặp không INSERT; UNIQUE vẫn phân xử hai request đầu đồng thời.
     * Chỉ CODE_COLLISION mới được thử mã khác; không suy ra quyền từ việc tài khoản đã tồn tại.
     * Bên gọi phải chạy ngoài transaction read-only hoặc transaction có isolation khác;
     * transaction này thuộc bước đồng bộ danh tính, không thuộc use case đọc của endpoint.
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AccountDetail resolve(ResolveAccountCommand command) {
        Validations.required(command, "command");
        var existing = read.findBySubject(command.keycloakSubject());
        if (existing.isPresent()) return reconcile(command, existing.orElseThrow());
        Instant createdAt = clock.instant();
        for (int attempt = 0; attempt < MAX_CODE_GENERATION_ATTEMPTS; attempt++) {
            Account candidate = Account.create(codes.generate("TK"), command.keycloakSubject(),
                    command.phoneNumber(), command.roles(), command.consent());
            WriteAccountPort.InsertResult result = write.insert(candidate, createdAt);
            if (result == WriteAccountPort.InsertResult.CODE_COLLISION) continue;
            if (result == null) throw new IllegalStateException("Account insert returned no result.");
            return reconcile(command, reload(command.keycloakSubject()));
        }
        throw new IllegalStateException("Failed to create an account with a unique code after 5 attempts.");
    }

    /** Đối chiếu trước khi bổ sung; luôn đọc lại sau UPDATE, kể cả khi bên kia đã ghi trước. */
    private AccountDetail reconcile(ResolveAccountCommand command, Account stored) {
        Account verified = verify(stored, command);
        if (stored.consent().isEmpty() && verified.consent().isPresent()) {
            write.addConsentIfAbsent(verified);
            stored = reload(command.keycloakSubject());
            verify(stored, command);
            if (stored.consent().isEmpty()) {
                throw new IllegalStateException("Account consent could not be reloaded after the write.");
            }
        }
        return AccountDetail.from(stored);
    }

    /** Không trả aggregate tự dựng nếu CSDL không có kết quả ghi; lỗi này phải rollback. */
    private Account reload(String subject) {
        return read.findBySubject(subject).orElseThrow(
                () -> new IllegalStateException("Resolved account could not be reloaded."));
    }

    /** Ủy quyền cho domain bảo vệ định danh và consent, không cập nhật âm thầm số/subject. */
    private Account verify(Account account, ResolveAccountCommand command) {
        return account.withAuthenticatedIdentity(command.keycloakSubject(), command.phoneNumber(),
                command.roles(), command.consent());
    }
}
