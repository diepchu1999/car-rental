package com.carrental.branch.application.service;

import com.carrental.branch.application.command.CreateBranchCommand;
import com.carrental.branch.application.port.out.ReadBranchPort;
import com.carrental.branch.application.port.out.WriteBranchPort;
import com.carrental.branch.application.view.BranchDetail;
import com.carrental.identity.api.CallerDirectory;
import com.carrental.identity.api.CallerRef;
import com.carrental.shared.code.BusinessCodeGenerator;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** BR-810: kiểm trực tiếp use case, không dựa vào controller hoặc Security để bảo vệ thao tác ghi. */
class BranchCreationAuthorizationTest {
    private static final CreateBranchCommand COMMAND = CreateBranchCommand.from(
            10.76, 106.66, "Test Branch", "Local test address");
    private final WriteBranchPort write = mock(WriteBranchPort.class);
    private final ReadBranchPort read = mock(ReadBranchPort.class);
    private final BusinessCodeGenerator codes = mock(BusinessCodeGenerator.class);
    private final CallerDirectory callers = mock(CallerDirectory.class);
    private final BranchCommandService service = new BranchCommandService(write, read, codes, callers);

    /** ADMIN có hoặc không có vai CUSTOMER vẫn tạo được; không bắt có chi nhánh để tạo chi nhánh. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void adminCanCreateWithoutBranchAssignment(boolean alsoCustomer) {
        when(callers.current()).thenReturn(new CallerRef("TK-ADMIN1",
                alsoCustomer ? Set.of("ADMIN", "CUSTOMER") : Set.of("ADMIN"), null));
        when(codes.generate("CN")).thenReturn("CN-LOCAL1");
        when(write.insert(any())).thenReturn(true);
        var detail = new BranchDetail(42L, "CN-LOCAL1", 10.76, 106.66, "Test Branch", "Local test address");
        when(read.findByCode("CN-LOCAL1")).thenReturn(Optional.of(detail));
        assertSame(detail, service.create(COMMAND));
        var order = inOrder(callers, codes, write, read);
        order.verify(callers).current();
        order.verify(codes).generate("CN");
        order.verify(write).insert(any());
        order.verify(read).findByCode("CN-LOCAL1");
        verifyNoMoreInteractions(callers, codes, write, read);
    }

    /** Mọi vai khác, kể cả quản lý/cấp công ty, đều bị từ chối trước sinh mã và mọi I/O chi nhánh. */
    @ParameterizedTest
    @ValueSource(strings = {"CUSTOMER", "BRANCH_STAFF", "BRANCH_MANAGER", "DELIVERY_STAFF", "DRIVER",
            "ACCOUNTANT", "OPERATIONS_MANAGER", "HOST", "BUSINESS_PARTNER", "BUSINESS_PARTNER_STAFF", "FREELANCE_DRIVER"})
    void nonAdminCannotCreateEvenWithBranchAssignment(String role) {
        when(callers.current()).thenReturn(new CallerRef("TK-OTHER1", Set.of(role), 42L));
        var failure = assertThrowsExactly(DomainException.class, () -> service.create(COMMAND));
        assertEquals(ErrorCode.ACCESS_DENIED, failure.errorCode());
        assertEquals(DomainException.Category.FORBIDDEN, failure.category());
        verifyNoInteractions(codes, write, read);
    }

    /** Danh tính không có vai không được xem là ADMIN mặc định. */
    @Test
    void callerWithoutRolesCannotCreate() {
        when(callers.current()).thenReturn(new CallerRef("TK-OTHER1", Set.of(), null));
        var failure = assertThrowsExactly(DomainException.class, () -> service.create(COMMAND));
        assertEquals(ErrorCode.ACCESS_DENIED, failure.errorCode());
        verifyNoInteractions(codes, write, read);
    }

    /** Lỗi chưa xác thực từ cổng giữ nguyên, không đổi thành quyền ADMIN hay bắt đầu ghi. */
    @Test
    void unauthenticatedCallerCannotCreate() {
        var failure = DomainException.unauthenticated();
        when(callers.current()).thenThrow(failure);
        assertSame(failure, assertThrowsExactly(DomainException.class, () -> service.create(COMMAND)));
        verifyNoInteractions(codes, write, read);
    }

    /** Ngoài HTTP không tự tạo người gọi hệ thống; lỗi cổng danh tính được truyền nguyên trạng. */
    @Test
    void invocationOutsideRequestCannotCreate() {
        var failure = new IllegalStateException("Caller is only available in an HTTP request.");
        when(callers.current()).thenThrow(failure);
        assertSame(failure, assertThrowsExactly(IllegalStateException.class, () -> service.create(COMMAND)));
        verifyNoInteractions(codes, write, read);
    }
}
