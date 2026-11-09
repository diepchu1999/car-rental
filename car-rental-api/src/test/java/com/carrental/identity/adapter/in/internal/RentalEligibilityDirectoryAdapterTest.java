package com.carrental.identity.adapter.in.internal;

import com.carrental.identity.application.port.in.CheckRentalEligibilityUseCase;
import com.carrental.identity.application.query.CheckRentalEligibilityQuery;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** ADR-0004/0008: cổng cross-module không làm mất đầu vào, lỗi nghiệp vụ hoặc bỏ validation. */
class RentalEligibilityDirectoryAdapterTest {
    private final CheckRentalEligibilityUseCase useCase = mock(CheckRentalEligibilityUseCase.class);
    private final RentalEligibilityDirectoryAdapter adapter = new RentalEligibilityDirectoryAdapter(useCase);
    private static final Instant END = Instant.parse("2030-01-01T03:00:00Z");

    /** Chuyển nguyên tài khoản/số chỗ/hộp số/giờ trả thực tế vào use case, không cần ngữ cảnh HTTP. */
    @Test void delegatesExactInput() {
        adapter.requireSelfDriveEligible("TK-OWNER1", 16, "MANUAL", END);
        verify(useCase).check(new CheckRentalEligibilityQuery("TK-OWNER1", 16, "MANUAL", END)); verifyNoMoreInteractions(useCase);
    }

    /** Lỗi nghiệp vụ giữ nguyên mã/nhóm, không được đổi thành boolean hoặc lỗi chung. */
    @Test void propagatesExactFailure() {
        var query = new CheckRentalEligibilityQuery("TK-OWNER1", 16, "MANUAL", END);
        var failure = DomainException.ruleViolation(ErrorCode.DRIVER_LICENSE_NOT_SUITABLE_FOR_VEHICLE);
        doThrow(failure).when(useCase).check(query);
        assertSame(failure, assertThrows(DomainException.class, () -> adapter.requireSelfDriveEligible("TK-OWNER1", 16, "MANUAL", END)));
    }

    /** Đầu vào không hợp lệ bị chặn trước use case, không chờ JDBC hoặc domain đoán hộp số. */
    @Test void rejectsInvalidInputBeforeUseCase() {
        assertEquals(ErrorCode.INVALID_REQUEST, assertThrows(DomainException.class,
                () -> adapter.requireSelfDriveEligible("TK-OWNER1", 7, "INVALID", END)).errorCode());
        verifyNoInteractions(useCase);
    }
}
