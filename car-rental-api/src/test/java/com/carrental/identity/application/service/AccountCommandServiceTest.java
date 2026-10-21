package com.carrental.identity.application.service;

import com.carrental.identity.application.command.ResolveAccountCommand;
import com.carrental.identity.application.port.out.ReadAccountPort;
import com.carrental.identity.application.port.out.WriteAccountPort;
import com.carrental.identity.application.view.AccountDetail;
import com.carrental.identity.domain.Account;
import com.carrental.identity.domain.AccountRole;
import com.carrental.identity.domain.TermsConsent;
import com.carrental.identity.domain.VietnamMobileNumber;
import com.carrental.shared.code.BusinessCodeGenerator;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import static com.carrental.identity.application.port.out.WriteAccountPort.InsertResult.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Kiểm điều phối, giới hạn thử mã và đọc lại consent; không dùng mock để chứng minh tính đồng thời. */
class AccountCommandServiceTest {
    private static final Instant AT = Instant.parse("2026-10-13T03:00:00Z");
    private static final TermsConsent CONSENT = new TermsConsent("local-test-v1", AT.minusSeconds(60));
    private final WriteAccountPort write = mock(WriteAccountPort.class);
    private final ReadAccountPort read = mock(ReadAccountPort.class);
    private final BusinessCodeGenerator codes = mock(BusinessCodeGenerator.class);
    private final AccountCommandService service = new AccountCommandService(write, read, codes, Clock.fixed(AT, ZoneOffset.UTC));

    /** Mỗi test có mã hợp lệ cố định, không phụ thuộc ngẫu nhiên hay đồng hồ hệ thống. */
    @BeforeEach
    void configureCode() { when(codes.generate("TK")).thenReturn("TK-NEW001"); }

    /** Chèn thành công vẫn trả view đọc lại, giữ thời điểm tạo và consent khác nhau. */
    @Test
    void insertsAndReturnsReloadedView() {
        when(write.insert(any(), eq(AT))).thenReturn(INSERTED);
        Account stored = stored("TK-NEW001", CONSENT);
        when(read.findBySubject("subject")).thenReturn(Optional.empty(), Optional.of(stored));
        assertEquals(AccountDetail.from(stored), service.resolve(customer(CONSENT)));
        var inserted = ArgumentCaptor.forClass(Account.class);
        verify(write).insert(inserted.capture(), eq(AT));
        assertEquals("TK-NEW001", inserted.getValue().code());
        assertEquals(CONSENT, inserted.getValue().consent().orElseThrow());
        var order = inOrder(write, read);
        order.verify(read).findBySubject("subject");
        order.verify(write).insert(any(), eq(AT));
        order.verify(read).findBySubject("subject");
        verify(write, never()).addConsentIfAbsent(any());
    }

    /** Subject đã có không sinh lại mã hay thay consent cũ bằng bản mới trong claims. */
    @Test
    void returnsExistingAccountAndOriginalConsent() {
        Account stored = stored("TK-OLD001", CONSENT);
        when(read.findBySubject("subject")).thenReturn(Optional.of(stored));
        assertEquals(AccountDetail.from(stored), service.resolve(customer(new TermsConsent("local-test-v2", AT))));
        verifyNoInteractions(codes, write);
    }

    /** Đọc hụt vì cuộc đua vẫn phải chấp nhận mã của bên thắng ON CONFLICT, không trả mã tự sinh. */
    @Test
    void reloadsConcurrentWinnerAfterInitiallyMissingSubject() {
        Account winner = stored("TK-OLD001", CONSENT);
        when(read.findBySubject("subject")).thenReturn(Optional.empty(), Optional.of(winner));
        when(write.insert(any(), eq(AT))).thenReturn(SUBJECT_EXISTS);
        assertEquals(AccountDetail.from(winner), service.resolve(customer(CONSENT)));
        verify(write).insert(any(), eq(AT));
        verify(read, times(2)).findBySubject("subject");
    }

    /** Được thành công ở lần thứ năm, các lần thử chỉ đổi mã, không đổi danh tính hoặc thời điểm. */
    @Test
    void retriesOnlyCodeCollisionAndCanSucceedOnFifthAttempt() {
        when(codes.generate("TK")).thenReturn("TK-TRY001", "TK-TRY002", "TK-TRY003", "TK-TRY004", "TK-TRY005");
        when(write.insert(any(), eq(AT))).thenReturn(CODE_COLLISION, CODE_COLLISION, CODE_COLLISION, CODE_COLLISION, INSERTED);
        when(read.findBySubject("subject")).thenReturn(Optional.empty(), Optional.of(stored("TK-TRY005", CONSENT)));
        assertEquals("TK-TRY005", service.resolve(customer(CONSENT)).code());
        var inserted = ArgumentCaptor.forClass(Account.class);
        verify(write, times(5)).insert(inserted.capture(), eq(AT));
        assertEquals(5, inserted.getAllValues().stream().map(Account::code).distinct().count());
        for (Account account : inserted.getAllValues()) {
            assertEquals("subject", account.keycloakSubject());
            assertEquals("+84912345678", account.phoneNumber().value());
            assertEquals(CONSENT, account.consent().orElseThrow());
        }
        verify(read, times(2)).findBySubject("subject");
    }

    /** Trùng cả năm lần dừng bằng lỗi nội bộ, không thử lần thứ sáu hoặc đọc tài khoản đoán mò. */
    @Test
    void stopsAfterExactlyFiveCodeCollisions() {
        when(write.insert(any(), eq(AT))).thenReturn(CODE_COLLISION);
        assertThrowsExactly(IllegalStateException.class, () -> service.resolve(customer(CONSENT)));
        verify(write, times(5)).insert(any(), eq(AT));
        verify(codes, times(5)).generate("TK");
        verify(read).findBySubject("subject");
    }

    /** Xung đột danh tính hoặc lỗi lưu trữ phải đi nguyên ra ngoài, không bị thử lại như trùng mã. */
    @Test
    void propagatesFailuresWithoutRetry() {
        for (RuntimeException failure : new RuntimeException[]{
                DomainException.conflict(ErrorCode.ACCOUNT_IDENTITY_CONFLICT), new IllegalStateException("Storage unavailable.")}) {
            reset(write, read, codes);
            configureCode();
            when(write.insert(any(), eq(AT))).thenThrow(failure);
            assertSame(failure, assertThrows(RuntimeException.class, () -> service.resolve(customer(CONSENT))));
            verify(write).insert(any(), eq(AT));
            verify(codes).generate("TK");
            verify(read).findBySubject("subject");
        }
    }

    /** Sai subject hoặc số bị từ chối trước UPDATE consent; không âm thầm hợp nhất hai danh tính. */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rejectsIdentityMismatchBeforeConsentWrite(boolean changedSubject) {
        Account mismatch = Account.restore("TK-OLD001", changedSubject ? "other-subject" : "subject",
                new VietnamMobileNumber(changedSubject ? "0912345678" : "0912345679"), null);
        when(read.findBySubject("subject")).thenReturn(Optional.of(mismatch));
        var failure = assertThrowsExactly(DomainException.class, () -> service.resolve(customer(CONSENT)));
        assertEquals(ErrorCode.ACCOUNT_IDENTITY_MISMATCH, failure.errorCode());
        assertEquals(DomainException.Category.FORBIDDEN, failure.category());
        verify(write, never()).addConsentIfAbsent(any());
    }

    /** UPDATE thắng hay thua đều phải đọc lại, trả trọn cặp bằng chứng thực sự đã lưu. */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void reloadsConsentAfterBothUpdateOutcomes(boolean changed) {
        TermsConsent winning = changed ? CONSENT : new TermsConsent("local-test-other", AT.minusSeconds(30));
        Account reloaded = stored("TK-OLD001", winning);
        when(read.findBySubject("subject")).thenReturn(Optional.of(stored("TK-OLD001", null)), Optional.of(reloaded));
        when(write.addConsentIfAbsent(any())).thenReturn(changed);
        assertEquals(AccountDetail.from(reloaded), service.resolve(customer(CONSENT)));
        verify(read, times(2)).findBySubject("subject");
        var update = ArgumentCaptor.forClass(Account.class);
        verify(write).addConsentIfAbsent(update.capture());
        assertEquals(CONSENT, update.getValue().consent().orElseThrow());
        assertEquals("TK-OLD001", update.getValue().code());
    }

    /** Nhân viên chưa thuê giữ consent null; không gọi cổng bổ sung bằng chứng. */
    @Test
    void employeeDoesNotAcquireInventedConsent() {
        when(write.insert(any(), eq(AT))).thenReturn(INSERTED);
        when(read.findBySubject("subject")).thenReturn(Optional.empty(), Optional.of(stored("TK-NEW001", null)));
        var detail = service.resolve(ResolveAccountCommand.from("subject", "0912345678", Set.of(AccountRole.ADMIN), null, null));
        assertNull(detail.termsVersion());
        assertNull(detail.consentedAt());
        verify(write, never()).addConsentIfAbsent(any());
        assertEquals("AccountDetail[code=TK-NEW001]", detail.toString());
    }

    /** Thiếu dữ liệu reload là lỗi nội bộ, không trả candidate đang giữ trong bộ nhớ. */
    @Test
    void missingReloadIsAnInternalFailure() {
        when(write.insert(any(), eq(AT))).thenReturn(INSERTED);
        when(read.findBySubject("subject")).thenReturn(Optional.empty());
        assertThrowsExactly(IllegalStateException.class, () -> service.resolve(customer(CONSENT)));
        verify(write, never()).addConsentIfAbsent(any());
    }

    /** Không coi việc UPDATE trả về là đủ nếu đọc lại vẫn chưa có bằng chứng. */
    @Test
    void missingConsentAfterWriteIsAnInternalFailure() {
        when(read.findBySubject("subject")).thenReturn(Optional.of(stored("TK-OLD001", null)));
        assertThrowsExactly(IllegalStateException.class, () -> service.resolve(customer(CONSENT)));
        verify(write).addConsentIfAbsent(any());
        verify(read, times(2)).findBySubject("subject");
    }

    /** Command null không được tới bộ sinh mã hoặc lưu trữ. */
    @Test
    void rejectsMissingCommandBeforeAnyWrite() {
        assertEquals(ErrorCode.INVALID_REQUEST, assertThrowsExactly(DomainException.class, () -> service.resolve(null)).errorCode());
        verifyNoInteractions(write, read);
        verify(codes, never()).generate(anyString());
    }

    /** Dựng tài khoản đã lưu với định danh cố định để kiểm riêng điều phối. */
    private static Account stored(String code, TermsConsent consent) {
        return Account.restore(code, "subject", new VietnamMobileNumber("0912345678"), consent);
    }

    /** Dựng claims khách đã kiểm ở biên, không sử dụng token hoặc dữ liệu cá nhân thật. */
    private static ResolveAccountCommand customer(TermsConsent consent) {
        return ResolveAccountCommand.from("subject", "0912345678", Set.of(AccountRole.CUSTOMER), consent.version(), consent.consentedAt());
    }
}
