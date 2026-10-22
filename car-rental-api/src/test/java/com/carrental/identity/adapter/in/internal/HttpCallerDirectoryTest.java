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

/** Kiểm cách đọc ngữ cảnh và cách ly request; chưa phải kiểm xác thực JWT ở biên HTTP. */
class HttpCallerDirectoryTest {
    private final HttpCallerDirectory directory = new HttpCallerDirectory();

    /** Không cho ngữ cảnh từ test trước tác động kết quả. */
    @BeforeEach
    void resetBeforeTest() { RequestContextHolder.resetRequestAttributes(); }

    /** Không để request giả tồn tại trên thread chạy test sau khi kết thúc. */
    @AfterEach
    void clearAfterTest() { RequestContextHolder.resetRequestAttributes(); }

    /** Gọi từ job hoặc ngoài HTTP phải báo lỗi rõ ràng, không giả danh ADMIN hoặc trả caller rỗng. */
    @Test
    void failsExplicitlyOutsideHttpRequest() {
        var failure = assertThrowsExactly(IllegalStateException.class, directory::current);
        assertEquals("CallerDirectory requires an active HTTP request.", failure.getMessage());
    }

    /** Có HTTP nhưng chưa có danh tính tin cậy phải trả đúng UNAUTHENTICATED. */
    @Test
    void missingAuthenticationIsRejected() {
        bind(new MockHttpServletRequest());
        assertUnauthenticated();
    }

    /** Header và query tự khai, kể cả trùng tên thuộc tính nội bộ, không trở thành danh tính máy chủ. */
    @Test
    void clientSuppliedHeadersAndParametersCannotForgeCaller() {
        var request = new MockHttpServletRequest();
        request.addHeader("X-Account-Code", "TK-ABC123");
        request.addHeader("X-Role", "ADMIN");
        request.addHeader("X-Branch-Id", "42");
        request.addHeader(CallerRef.class.getName(), "ADMIN");
        request.addParameter(CallerRef.class.getName(), "ADMIN");
        request.addParameter("roles", "ADMIN");
        bind(request);
        assertUnauthenticated();
    }

    /** Chỉ trả đúng đối tượng mà biên xác thực đã đặt trong request hiện tại. */
    @Test
    void returnsTheTrustedRequestAttribute() {
        var caller = new CallerRef("TK-ABC123", Set.of("BRANCH_STAFF"), 42L);
        bind(requestFor(caller));
        assertSame(caller, directory.current());
    }

    /** Đặt sai kiểu là lỗi phía máy chủ, không tự parse JSON/chuỗi rồi nhận làm caller. */
    @Test
    void malformedServerAttributeIsAnInternalFailure() {
        var request = new MockHttpServletRequest();
        request.setAttribute(CallerRef.class.getName(), "ADMIN");
        bind(request);
        assertThrowsExactly(IllegalStateException.class, directory::current);
    }

    /** Dùng lại cùng thread cho request tiếp theo không lấy được danh tính request trước. */
    @Test
    void consecutiveRequestsDoNotReuseThePreviousCaller() {
        var first = new CallerRef("TK-ABC123", Set.of("ADMIN"), null);
        bind(requestFor(first));
        assertSame(first, directory.current());
        RequestContextHolder.resetRequestAttributes();
        bind(new MockHttpServletRequest());
        assertUnauthenticated();
    }

    /** Hai request giữ ngữ cảnh cùng lúc vẫn trả đúng mã, vai và chi nhánh riêng của từng thread. */
    @Test
    void concurrentRequestsDoNotMixCallers() throws Exception {
        var first = new CallerRef("TK-ABC123", Set.of("BRANCH_STAFF"), 42L);
        var second = new CallerRef("TK-DEF456", Set.of("BRANCH_MANAGER"), 43L);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var firstResult = workers.submit(() -> readInWorker(first, ready, start));
            var secondResult = workers.submit(() -> readInWorker(second, ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(first, firstResult.get(5, TimeUnit.SECONDS));
            assertEquals(second, secondResult.get(5, TimeUnit.SECONDS));
            assertNull(RequestContextHolder.getRequestAttributes());
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /** Thread nền sinh trong request không được kế thừa danh tính người gọi. */
    @Test
    void backgroundThreadCannotInheritHttpCaller() throws Exception {
        bind(requestFor(new CallerRef("TK-ABC123", Set.of("ADMIN"), null)));
        var workers = Executors.newSingleThreadExecutor();
        try {
            workers.submit(() -> assertThrowsExactly(IllegalStateException.class, directory::current))
                    .get(5, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /** Giữ ngữ cảnh trên thread riêng tới khi cả hai sẵn sàng; luôn xoá ở finally. */
    private CallerRef readInWorker(CallerRef caller, CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        bind(requestFor(caller));
        try {
            ready.countDown();
            assertTrue(start.await(5, TimeUnit.SECONDS));
            return directory.current();
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    /** Mô phỏng thuộc tính phía máy chủ do bộ lọc xác thực đặt, không tạo endpoint bỏ qua bảo mật. */
    private static MockHttpServletRequest requestFor(CallerRef caller) {
        var request = new MockHttpServletRequest();
        request.setAttribute(CallerRef.class.getName(), caller);
        return request;
    }

    /** Gắn request không cho thread con kế thừa, đúng vòng đời theo request. */
    private static void bind(MockHttpServletRequest request) {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request), false);
    }

    /** Kiểm cả mã lẫn nhóm lỗi để không lẫn chưa đăng nhập với lỗi quyền hoặc lỗi nội bộ. */
    private void assertUnauthenticated() {
        var failure = assertThrowsExactly(DomainException.class, directory::current);
        assertEquals(ErrorCode.UNAUTHENTICATED, failure.errorCode());
        assertEquals(DomainException.Category.UNAUTHENTICATED, failure.category());
    }
}
