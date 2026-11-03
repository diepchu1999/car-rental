package com.carrental.identity.application.service;

import com.carrental.identity.application.port.out.ReadDocumentSubmissionPort;
import com.carrental.identity.application.port.out.ReadLicenseClassPolicyPort;
import com.carrental.identity.application.query.CheckRentalEligibilityQuery;
import com.carrental.identity.application.view.ActiveDocumentDetail;
import com.carrental.identity.domain.DocumentType;
import com.carrental.identity.domain.LicenseClassTable;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Điều phối cổng tự lái: đồng hồ/múi giờ, snapshot active, config theo thời điểm và lỗi không bị nuốt. */
class RentalEligibilityQueryServiceTest {
    private static final String OWNER = "TK-OWNER1";
    private static final Instant NOW = Instant.parse("2026-10-31T03:00:00Z");
    private static final Instant END = Instant.parse("2030-01-01T16:59:59.999999Z");
    private final ReadDocumentSubmissionPort reads = mock(ReadDocumentSubmissionPort.class);
    private final ReadLicenseClassPolicyPort policies = mock(ReadLicenseClassPolicyPort.class);
    private final Clock clock = Clock.fixed(NOW, ZoneId.of("Asia/Ho_Chi_Minh"));
    private final RentalEligibilityQueryService service = new RentalEligibilityQueryService(reads, policies, clock);

    /** Một lần đọc cả hai bản; config dùng mốc kiểm hiện tại, không dùng giờ trả xe. */
    @Test void readsOneSnapshotAndPolicyAtEvaluationTime() {
        ready(); service.check(query(END));
        verify(reads).listActiveByOwner(OWNER); verify(policies).tableAt(NOW); verifyNoMoreInteractions(reads, policies);
    }

    /** 17:00 UTC là ngày mới ở VN; không dùng UTC/JVM và không trừ nano ở giờ trả xe. */
    @Test void rejectsVietnamMidnightAfterExpiry() {
        ready(); var failure = assertThrows(DomainException.class, () -> service.check(query(Instant.parse("2030-01-01T17:00:00Z"))));
        assertEquals(ErrorCode.IDENTITY_CARD_EXPIRES_BEFORE_TRIP_END, failure.errorCode());
    }

    /** Không cache metadata/config: lần sau bản active hoặc bảng hạng đã đổi phải được xét lại. */
    @Test void rereadsPolicyAndEvidenceOnEveryCall() {
        ready(); service.check(query(END));
        when(policies.tableAt(NOW)).thenReturn(new LicenseClassTable(Map.of("D", Set.of(7))));
        assertEquals(ErrorCode.DRIVER_LICENSE_CLASS_NOT_SUPPORTED,
                assertThrows(DomainException.class, () -> service.check(query(END))).errorCode());
        when(reads.listActiveByOwner(OWNER)).thenReturn(List.of());
        assertEquals(ErrorCode.IDENTITY_CARD_NOT_VERIFIED,
                assertThrows(DomainException.class, () -> service.check(query(END))).errorCode());
        verify(reads, times(3)).listActiveByOwner(OWNER); verify(policies, times(3)).tableAt(NOW);
    }

    /** Lỗi đọc hoặc cấu hình phải giữ nguyên, không được coi như đủ điều kiện hoặc thiếu hồ sơ. */
    @Test void propagatesInfrastructureFailures() {
        ready(); var failure = new IllegalStateException("Forced read failure.");
        when(reads.listActiveByOwner(OWNER)).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.check(query(END))));
        verifyNoInteractions(policies);
        reset(reads, policies); ready(); when(policies.tableAt(NOW)).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.check(query(END))));
    }

    /** Trùng loại/null/hạn null từ adapter là lỗi nội bộ; không chọn bừa hàng đầu hoặc coi null là chưa xác minh. */
    @Test void rejectsCorruptReadResults() {
        var card = card();
        for (List<ActiveDocumentDetail> rows : List.of(List.of(card, card), Arrays.asList((ActiveDocumentDetail) null),
                List.of(new ActiveDocumentDetail("GT-FAKE01", null, LocalDate.of(2030, 1, 1), null, null, null)),
                List.of(new ActiveDocumentDetail("GT-FAKE01", DocumentType.IDENTITY_CARD, null, null, null, null)))) {
            when(reads.listActiveByOwner(OWNER)).thenReturn(rows);
            assertThrows(IllegalStateException.class, () -> service.check(query(END)));
        }
        verifyNoInteractions(policies);
    }

    /** Instant ngoài miền LocalDate không được thành lỗi 500; kiểm trước đọc CSDL. */
    @Test void rejectsUnrepresentableCalendarDates() {
        for (var end : List.of(Instant.MIN, Instant.MAX)) assertInvalid(() -> service.check(query(end)));
        verifyNoInteractions(reads, policies);
    }

    /** Mã TK không đúng định dạng không được normalize hoặc đưa vào thông báo. */
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" ", "TK-X", "tk-OWNER1", "TK-OWNER1 ", "17"})
    void rejectsInvalidAccountCodes(String code) {
        assertInvalid(() -> CheckRentalEligibilityQuery.from(code, 7, "AUTOMATIC", END));
    }

    /** Hộp số bắt buộc thuộc hợp đồng vehicle; không biến giá trị lạ thành tự động. */
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"manual", "UNKNOWN", " MANUAL"})
    void rejectsInvalidTransmission(String transmission) {
        assertInvalid(() -> new CheckRentalEligibilityQuery(OWNER, 7, transmission, END));
    }

    /** Số chỗ ngoài BR-018 giữ mã lỗi riêng, kể cả constructor được gọi trực tiếp. */
    @ParameterizedTest @ValueSource(ints = {-1, 0, 6, 8, 17, Integer.MAX_VALUE})
    void rejectsInvalidSeats(int seats) {
        var failure = assertThrows(DomainException.class, () -> new CheckRentalEligibilityQuery(OWNER, seats, "MANUAL", END));
        assertEquals(ErrorCode.VEHICLE_INVALID_SEATS, failure.errorCode());
        assertEquals(DomainException.Category.RULE_VIOLATION, failure.category());
    }

    /** Không bỏ giờ trả; record không in dữ liệu thuê vào log. */
    @Test void requiresTripEndAndRedactsQuery() {
        assertInvalid(() -> CheckRentalEligibilityQuery.from(OWNER, 7, "AUTOMATIC", null));
        assertEquals("CheckRentalEligibilityQuery[redacted]", query(END).toString());
    }

    /** Fixture VERIFIED có hạn hết ngày 01/01/2030; thứ tự danh sách cố ý GPLX trước CCCD. */
    private void ready() {
        when(reads.listActiveByOwner(OWNER)).thenReturn(List.of(
                new ActiveDocumentDetail("GT-FAKE02", DocumentType.DRIVER_LICENSE, LocalDate.of(2030, 1, 1), "B", false, LocalDate.of(2025, 1, 1)), card()));
        when(policies.tableAt(NOW)).thenReturn(new LicenseClassTable(Map.of("B", Set.of(4, 5, 7))));
    }

    /** Metadata CCCD tối thiểu, không có số hoặc thông tin nhận dạng. */
    private static ActiveDocumentDetail card() {
        return new ActiveDocumentDetail("GT-FAKE01", DocumentType.IDENTITY_CARD, LocalDate.of(2030, 1, 1), null, null, null);
    }

    /** Tạo đầu vào hợp lệ từ API nội bộ. */
    private static CheckRentalEligibilityQuery query(Instant end) {
        return CheckRentalEligibilityQuery.from(OWNER, 7, "AUTOMATIC", end);
    }

    /** Kiểm đúng nhóm 400, không vô tình chấp nhận NPE hoặc lỗi CSDL làm bằng chứng. */
    private static void assertInvalid(org.junit.jupiter.api.function.Executable action) {
        var failure = assertThrows(DomainException.class, action);
        assertEquals(ErrorCode.INVALID_REQUEST, failure.errorCode()); assertEquals(DomainException.Category.INVALID_INPUT, failure.category());
    }
}
