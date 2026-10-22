package com.carrental.identity.application.service;

import com.carrental.branch.api.BranchDirectory;
import com.carrental.branch.api.BranchRef;
import com.carrental.identity.api.CallerDirectory;
import com.carrental.identity.api.CallerRef;
import com.carrental.identity.application.port.out.ReadAccountPort;
import com.carrental.identity.application.view.CurrentAccountDetail;
import com.carrental.identity.domain.Account;
import com.carrental.identity.domain.AccountRole;
import com.carrental.identity.domain.TermsConsent;
import com.carrental.identity.domain.VietnamMobileNumber;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Kiểm use case đọc chính mình bằng cổng giả, không giả lập xác thực JWT hoặc constraint CSDL.
 * Các test HTTP/decoder và lưu trữ đã kiểm riêng; ở đây tập trung vào dữ liệu được phép đọc và trả.
 */
class AccountQueryServiceTest {
    private static final String CODE = "TK-SELF01";
    private static final Instant CONSENTED_AT = Instant.parse("2026-10-12T03:00:00Z");
    private final CallerDirectory callers = mock(CallerDirectory.class);
    private final ReadAccountPort accounts = mock(ReadAccountPort.class);
    private final BranchDirectory branches = mock(BranchDirectory.class);
    private final AccountQueryService service = new AccountQueryService(callers, accounts, branches);

    /** Mã tra cứu chỉ đến từ caller; số điện thoại và consent lấy đúng bản lưu, không tự tính lại. */
    @Test
    void readsOwnAccountAndStoredConsent() {
        when(callers.current()).thenReturn(caller(Set.of("CUSTOMER"), null));
        when(accounts.findByCode(CODE)).thenReturn(Optional.of(account(CODE, true)));

        assertEquals(new CurrentAccountDetail(CODE, "+84912345678", Set.of("CUSTOMER"),
                null, "local-test-v1", CONSENTED_AT), service.get());

        var order = inOrder(callers, accounts);
        order.verify(callers).current();
        order.verify(accounts).findByCode(CODE);
        verifyNoMoreInteractions(callers, accounts);
        verifyNoInteractions(branches);
    }

    /** Cả bảy vai nhân viên được đọc chính mình khi chưa có consent hoặc chưa gán chi nhánh. */
    @ParameterizedTest
    @EnumSource(value = AccountRole.class, names = {"BRANCH_STAFF", "BRANCH_MANAGER", "DELIVERY_STAFF",
            "DRIVER", "ACCOUNTANT", "OPERATIONS_MANAGER", "ADMIN"})
    void permitsEmployeeWithoutConsentOrBranch(AccountRole role) {
        when(callers.current()).thenReturn(caller(Set.of(role.name()), null));
        when(accounts.findByCode(CODE)).thenReturn(Optional.of(account(CODE, false)));

        var result = service.get();

        assertEquals(CODE, result.code());
        assertEquals(Set.of(role.name()), result.roles());
        assertNull(result.branchCode());
        assertNull(result.termsVersion());
        assertNull(result.consentedAt());
        verifyNoInteractions(branches);
    }

    /** Chi nhánh hiện ra bằng mã qua hợp đồng public, không đưa ID số vào read model. */
    @Test
    void resolvesBranchCodeThroughDirectory() {
        when(callers.current()).thenReturn(caller(Set.of("BRANCH_STAFF"), 42L));
        when(accounts.findByCode(CODE)).thenReturn(Optional.of(account(CODE, false)));
        when(branches.findById(42L)).thenReturn(Optional.of(new BranchRef(42L, "CN-LOCAL1")));

        assertEquals("CN-LOCAL1", service.get().branchCode());

        verify(branches).findById(42L);
        verifyNoMoreInteractions(branches);
    }

    /** Hai vai của cùng một tài khoản không bị rút còn một vai hoặc suy thêm ADMIN. */
    @Test
    void preservesCustomerAndEmployeeRoles() {
        var roles = Set.of("CUSTOMER", "BRANCH_MANAGER");
        when(callers.current()).thenReturn(caller(roles, null));
        when(accounts.findByCode(CODE)).thenReturn(Optional.of(account(CODE, true)));

        assertEquals(roles, service.get().roles());
    }

    /** Chưa xác thực phải dừng trước mọi truy cập tài khoản hoặc chi nhánh. */
    @Test
    void rejectsUnauthenticatedCallerBeforeLookup() {
        var failure = DomainException.unauthenticated();
        when(callers.current()).thenThrow(failure);

        assertSame(failure, assertThrowsExactly(DomainException.class, service::get));
        assertEquals(ErrorCode.UNAUTHENTICATED, failure.errorCode());
        verifyNoInteractions(accounts, branches);
    }

    /** Ngoài HTTP giữ lỗi rõ ràng của CallerDirectory, không giả tài khoản mặc định cho job. */
    @Test
    void propagatesMissingRequestContext() {
        var failure = new IllegalStateException("CallerDirectory requires an active HTTP request.");
        when(callers.current()).thenThrow(failure);

        assertSame(failure, assertThrowsExactly(IllegalStateException.class, service::get));
        verifyNoInteractions(accounts, branches);
    }

    /** Không có tài khoản đã xác thực thì báo đúng ACCOUNT_NOT_FOUND, không đọc tài khoản dự phòng. */
    @Test
    void rejectsMissingAccount() {
        when(callers.current()).thenReturn(caller(Set.of("CUSTOMER"), null));
        when(accounts.findByCode(CODE)).thenReturn(Optional.empty());

        var failure = assertThrowsExactly(DomainException.class, service::get);

        assertEquals(ErrorCode.ACCOUNT_NOT_FOUND, failure.errorCode());
        assertEquals(DomainException.Category.NOT_FOUND, failure.category());
        verifyNoInteractions(branches);
    }

    /** Adapter trả nhầm tài khoản không được làm lộ số điện thoại và consent của người khác. */
    @Test
    void rejectsDifferentAccountReturnedByPort() {
        when(callers.current()).thenReturn(caller(Set.of("BRANCH_STAFF"), 42L));
        when(accounts.findByCode(CODE)).thenReturn(Optional.of(account("TK-OTHER1", true)));

        var failure = assertThrowsExactly(IllegalStateException.class, service::get);

        assertEquals("Account lookup returned a different account.", failure.getMessage());
        verifyNoInteractions(branches);
    }

    /** Có tham chiếu nhưng chi nhánh mất là lỗi toàn vẹn nội bộ, không trả branchCode null. */
    @Test
    void rejectsDanglingBranchReference() {
        when(callers.current()).thenReturn(caller(Set.of("BRANCH_STAFF"), 42L));
        when(accounts.findByCode(CODE)).thenReturn(Optional.of(account(CODE, false)));
        when(branches.findById(42L)).thenReturn(Optional.empty());

        var failure = assertThrowsExactly(IllegalStateException.class, service::get);

        assertEquals("Assigned branch could not be resolved.", failure.getMessage());
    }

    /** Directory trả sai ID hoặc thiếu mã không được trả một danh tính chi nhánh không đáng tin. */
    @Test
    void rejectsInconsistentBranchIdentity() {
        when(callers.current()).thenReturn(caller(Set.of("BRANCH_STAFF"), 42L));
        when(accounts.findByCode(CODE)).thenReturn(Optional.of(account(CODE, false)));
        for (var branch : new BranchRef[]{new BranchRef(99L, "CN-OTHER1"),
                new BranchRef(42L, null), new BranchRef(42L, " ")}) {
            when(branches.findById(42L)).thenReturn(Optional.of(branch));

            var failure = assertThrowsExactly(IllegalStateException.class, service::get);

            assertEquals("Branch lookup returned an inconsistent identity.", failure.getMessage());
        }
    }

    /** Lỗi đọc tài khoản không bị đổi thành 404 hoặc dữ liệu tài khoản rỗng. */
    @Test
    void propagatesAccountStorageFailure() {
        when(callers.current()).thenReturn(caller(Set.of("CUSTOMER"), null));
        var failure = new IllegalStateException("Account storage unavailable.");
        when(accounts.findByCode(CODE)).thenThrow(failure);

        assertSame(failure, assertThrowsExactly(IllegalStateException.class, service::get));
        verifyNoInteractions(branches);
    }

    /** Lỗi module branch không bị đổi thành chưa gán chi nhánh. */
    @Test
    void propagatesBranchLookupFailure() {
        when(callers.current()).thenReturn(caller(Set.of("BRANCH_STAFF"), 42L));
        when(accounts.findByCode(CODE)).thenReturn(Optional.of(account(CODE, false)));
        var failure = new IllegalStateException("Branch storage unavailable.");
        when(branches.findById(42L)).thenThrow(failure);

        assertSame(failure, assertThrowsExactly(IllegalStateException.class, service::get));
    }

    /** Đổi caller giữa các lượt gọi trên cùng service không lấy lại tài khoản hoặc vai của lượt trước. */
    @Test
    void doesNotCacheCurrentAccount() {
        when(callers.current()).thenReturn(caller(Set.of("CUSTOMER"), null),
                new CallerRef("TK-OTHER1", Set.of("ADMIN"), null));
        when(accounts.findByCode(CODE)).thenReturn(Optional.of(account(CODE, true)));
        when(accounts.findByCode("TK-OTHER1")).thenReturn(Optional.of(account("TK-OTHER1", false)));

        var first = service.get();
        var second = service.get();

        assertEquals(CODE, first.code());
        assertEquals(Set.of("CUSTOMER"), first.roles());
        assertEquals("TK-OTHER1", second.code());
        assertEquals(Set.of("ADMIN"), second.roles());
        assertNull(second.termsVersion());
        verify(callers, times(2)).current();
        verify(accounts).findByCode(CODE);
        verify(accounts).findByCode("TK-OTHER1");
        verifyNoMoreInteractions(accounts);
    }

    /** View giữ vai bất biến và biểu diễn log không chứa thông tin riêng tư. */
    @Test
    void keepsViewImmutableAndRedactsItsRepresentation() {
        var input = new HashSet<>(Set.of("CUSTOMER"));
        var view = new CurrentAccountDetail(CODE, "+84912345678", input,
                null, "local-test-v1", CONSENTED_AT);
        input.add("ADMIN");

        assertEquals(Set.of("CUSTOMER"), view.roles());
        assertThrows(UnsupportedOperationException.class, () -> view.roles().add("ADMIN"));
        assertEquals("CurrentAccountDetail[code=" + CODE + "]", view.toString());
    }

    /** Danh tính fixture đã xác thực, không gắn trực tiếp với HTTP hoặc Spring Security. */
    private CallerRef caller(Set<String> roles, Long branchId) {
        return new CallerRef(CODE, roles, branchId);
    }

    /** Dựng aggregate đã lưu; consent có thể thiếu ở nhân viên chưa đăng ký thuê theo BR-809. */
    private Account account(String code, boolean withConsent) {
        return Account.restore(code, "test-subject-" + code, new VietnamMobileNumber("+84912345678"),
                withConsent ? new TermsConsent("local-test-v1", CONSENTED_AT) : null);
    }
}
