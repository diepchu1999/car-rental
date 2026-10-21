package com.carrental.identity.adapter.in.internal;

import com.carrental.identity.api.CallerRef;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Kiểm fixture qua HttpCallerDirectory thật: không cấp quyền ngầm, không rò danh tính giữa các scope/thread. */
class ServiceCallerTestScopeTest {
    private final HttpCallerDirectory callers = new HttpCallerDirectory();

    /** Mỗi test bắt đầu ngoài request để không dựa vào danh tính từ lượt trước. */
    @BeforeEach
    void clearBefore() { RequestContextHolder.resetRequestAttributes(); }

    /** Dọn ngữ cảnh ngay cả khi assertion làm test thất bại. */
    @AfterEach
    void clearAfter() { RequestContextHolder.resetRequestAttributes(); }

    /** Chỉ thời gian mở scope mới có ADMIN; đóng xong adapter lại từ chối ngoài HTTP. */
    @Test
    void explicitAdminExistsOnlyInsideScope() {
        assertThrowsExactly(IllegalStateException.class, callers::current);
        try (var scope = ServiceCallerTestScope.admin()) {
            assertEquals(Set.of("ADMIN"), callers.current().roles());
            assertNull(callers.current().branchId());
        }
        assertNull(RequestContextHolder.getRequestAttributes());
        assertThrowsExactly(IllegalStateException.class, callers::current);
    }

    /** STAFF lồng trong ADMIN không được thêm quyền ADMIN; đóng trong khôi phục đúng caller ngoài. */
    @Test
    void nestedScopeKeepsExactRolesAndRestoresOuterCaller() {
        var staff = new CallerRef("TK-STAFF1", Set.of("BRANCH_STAFF"), 1000L);
        try (var outer = ServiceCallerTestScope.admin()) {
            var admin = callers.current();
            try (var inner = ServiceCallerTestScope.open(staff)) {
                assertSame(staff, callers.current());
                assertFalse(callers.current().hasRole("ADMIN"));
            }
            assertSame(admin, callers.current());
        }
        assertNull(RequestContextHolder.getRequestAttributes());
    }

    /** Callback lỗi vẫn khôi phục request ẩn danh ban đầu; không ghi caller lên request cũ. */
    @Test
    void exceptionRestoresOriginalUnauthenticatedRequest() {
        var request = new MockHttpServletRequest();
        var original = new ServletRequestAttributes(request);
        RequestContextHolder.setRequestAttributes(original, false);
        var expected = new IllegalStateException("Fixture setup failed.");
        assertSame(expected, assertThrowsExactly(IllegalStateException.class,
                () -> ServiceCallerTestScope.asAdmin(() -> { throw expected; })));
        assertSame(original, RequestContextHolder.getRequestAttributes());
        assertNull(request.getAttribute(CallerRef.class.getName()));
        assertEquals(ErrorCode.UNAUTHENTICATED,
                assertThrowsExactly(DomainException.class, callers::current).errorCode());
    }

    /** Không cho caller null hoặc callback null trở thành ADMIN mặc định. */
    @Test
    void rejectsMissingInputsWithoutBindingRequest() {
        assertThrowsExactly(NullPointerException.class, () -> ServiceCallerTestScope.open(null));
        assertThrowsExactly(NullPointerException.class, () -> ServiceCallerTestScope.asAdmin(null));
        assertNull(RequestContextHolder.getRequestAttributes());
    }

    /** Worker không kế thừa ADMIN và hai scope song song không trộn vai/chi nhánh hoặc để lại request. */
    @Test
    void workerScopesAreIsolatedAndDoNotInheritAdmin() throws Exception {
        var first = new CallerRef("TK-STAFF1", Set.of("BRANCH_STAFF"), 1000L);
        var second = new CallerRef("TK-MANAG1", Set.of("BRANCH_MANAGER"), 2000L);
        var ready = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try (var admin = ServiceCallerTestScope.admin()) {
            var a = workers.submit(() -> readInWorker(first, ready, release));
            var b = workers.submit(() -> readInWorker(second, ready, release));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            release.countDown();
            assertEquals(first, a.get(5, TimeUnit.SECONDS));
            assertEquals(second, b.get(5, TimeUnit.SECONDS));
            assertTrue(callers.current().hasRole("ADMIN"));
        } finally {
            release.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertNull(RequestContextHolder.getRequestAttributes());
    }

    /** Giữ cả hai scope mở cùng lúc, kiểm caller rồi kiểm dọn sạch ngay trên chính worker. */
    private CallerRef readInWorker(CallerRef caller, CountDownLatch ready, CountDownLatch release)
            throws InterruptedException {
        assertThrowsExactly(IllegalStateException.class, callers::current);
        CallerRef result;
        try (var scope = ServiceCallerTestScope.open(caller)) {
            ready.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            result = callers.current();
        }
        assertNull(RequestContextHolder.getRequestAttributes());
        assertThrowsExactly(IllegalStateException.class, callers::current);
        return result;
    }
}
