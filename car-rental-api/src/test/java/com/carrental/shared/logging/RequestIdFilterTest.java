package com.carrental.shared.logging;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Kiểm biên bảo mật header, vòng đời MDC và sự cách ly giữa các request. */
class RequestIdFilterTest {
    private final RequestIdFilter filter = new RequestIdFilter();
    private Map<String, String> previousContext;

    /** Cách ly MDC test khỏi thread mà JUnit tái sử dụng. */
    @BeforeEach
    void saveContext() {
        previousContext = MDC.getCopyOfContextMap();
        MDC.clear();
    }

    /** Trả lại ngữ cảnh ban đầu, không xóa MDC của test khác. */
    @AfterEach
    void restoreContext() {
        MDC.clear();
        if (previousContext != null) {
            MDC.setContextMap(previousContext);
        }
    }

    /** Không có header thì sinh UUID, chạy chain đúng một lần và dọn MDC sau khi xong. */
    @Test
    void generatesIdWhenHeaderIsMissing() throws Exception {
        var response = new MockHttpServletResponse();
        var invocations = new AtomicInteger();
        filter.doFilter(new MockHttpServletRequest(), response, (request, result) -> {
            invocations.incrementAndGet();
            assertGeneratedId(response.getHeader(RequestIdFilter.HEADER_NAME));
            assertEquals(response.getHeader(RequestIdFilter.HEADER_NAME), MDC.get(RequestIdFilter.MDC_KEY));
        });
        assertEquals(1, invocations.get());
        assertNull(MDC.get(RequestIdFilter.MDC_KEY));
    }

    /** ID an toàn được dùng nguyên vẹn, kể cả hai biên độ dài một và 64 ký tự. */
    @ParameterizedTest
    @ValueSource(strings = {"A", "0", "-", "client-ABC-123",
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"})
    void preservesSafeId(String suppliedId) throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER_NAME, suppliedId);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) ->
                assertEquals(suppliedId, MDC.get(RequestIdFilter.MDC_KEY)));
        assertEquals(suppliedId, response.getHeader(RequestIdFilter.HEADER_NAME));
        assertNull(MDC.get(RequestIdFilter.MDC_KEY));
    }

    /** CR/LF ở đây là ký tự thật, không phải chuỗi backslash; không giá trị sai nào vào MDC/response. */
    @ParameterizedTest
    @ValueSource(strings = {"", " ", " leading", "trailing ", "bad_id", "bad/id", "mã", "one,two",
            "bad\rFORGED", "bad\nFORGED", "bad\r\nFORGED", "bad\tvalue", "bad\u2028value",
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdefX"})
    void replacesUnsafeId(String suppliedId) throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER_NAME, suppliedId);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
            assertGeneratedId(MDC.get(RequestIdFilter.MDC_KEY));
            assertNotEquals(suppliedId, MDC.get(RequestIdFilter.MDC_KEY));
        });
        assertGeneratedId(response.getHeader(RequestIdFilter.HEADER_NAME));
        assertNotEquals(suppliedId, response.getHeader(RequestIdFilter.HEADER_NAME));
        assertNull(MDC.get(RequestIdFilter.MDC_KEY));
    }

    /** Nhiều header cùng tên bị từ chối để không phụ thuộc cách proxy gộp/chọn header. */
    @Test
    void replacesMultipleHeaderValues() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER_NAME, "first-safe-id");
        request.addHeader(RequestIdFilter.HEADER_NAME, "second-safe-id");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
            assertGeneratedId(MDC.get(RequestIdFilter.MDC_KEY));
            assertNotEquals("first-safe-id", MDC.get(RequestIdFilter.MDC_KEY));
            assertNotEquals("second-safe-id", MDC.get(RequestIdFilter.MDC_KEY));
        });
        assertGeneratedId(response.getHeader(RequestIdFilter.HEADER_NAME));
    }

    /** Không làm mất ngữ cảnh bao ngoài hoặc khóa MDC khác khi xử lý thành công. */
    @Test
    void restoresPreviousContextAfterSuccess() throws Exception {
        MDC.put(RequestIdFilter.MDC_KEY, "outer-id");
        MDC.put("otherContext", "preserved");
        var response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(), response, (request, result) -> {
            assertNotEquals("outer-id", MDC.get(RequestIdFilter.MDC_KEY));
            assertEquals("preserved", MDC.get("otherContext"));
        });
        assertEquals(Map.of("requestId", "outer-id", "otherContext", "preserved"), MDC.getCopyOfContextMap());
    }

    /** Lỗi chain được ném lại nguyên đối tượng và không để lại requestId trên thread. */
    @Test
    void clearsIdAndPreservesExceptionWhenChainFails() {
        IOException failure = new IOException("Test chain failure");
        var response = new MockHttpServletResponse();
        assertSame(failure, assertThrows(IOException.class, () ->
                filter.doFilter(new MockHttpServletRequest(), response, (request, result) -> {
                    assertGeneratedId(MDC.get(RequestIdFilter.MDC_KEY));
                    throw failure;
                })));
        assertGeneratedId(response.getHeader(RequestIdFilter.HEADER_NAME));
        assertNull(MDC.get(RequestIdFilter.MDC_KEY));
    }

    /** Nhánh lỗi cũng khôi phục giá trị MDC có từ trước, không chỉ xóa sạch. */
    @Test
    void restoresPreviousIdWhenChainFails() {
        MDC.put(RequestIdFilter.MDC_KEY, "outer-id");
        MDC.put("otherContext", "preserved");
        RuntimeException failure = new IllegalStateException("Test runtime failure");
        assertSame(failure, assertThrows(RuntimeException.class, () ->
                filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                        (request, response) -> { throw failure; })));
        assertEquals(Map.of("requestId", "outer-id", "otherContext", "preserved"), MDC.getCopyOfContextMap());
    }

    /** Hai request nối tiếp trên cùng thread không kế thừa ID đã sinh của nhau. */
    @Test
    void generatesSeparateIdsOnReusedThread() throws Exception {
        var first = new MockHttpServletResponse();
        var second = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(), first, (request, response) -> {});
        filter.doFilter(new MockHttpServletRequest(), second, (request, response) -> {});
        assertNotEquals(first.getHeader(RequestIdFilter.HEADER_NAME), second.getHeader(RequestIdFilter.HEADER_NAME));
        assertNull(MDC.get(RequestIdFilter.MDC_KEY));
    }

    /** Error và async dispatch trên thread khác vẫn dùng ID của request ban đầu. */
    @ParameterizedTest
    @EnumSource(value = DispatcherType.class, names = {"ERROR", "ASYNC"})
    void preservesIdAcrossRedispatch(DispatcherType dispatcherType) throws Exception {
        var request = new MockHttpServletRequest();
        var initialResponse = new MockHttpServletResponse();
        filter.doFilter(request, initialResponse, (ignoredRequest, ignoredResponse) -> {});
        String expectedId = initialResponse.getHeader(RequestIdFilter.HEADER_NAME);
        request.setDispatcherType(dispatcherType);
        if (dispatcherType == DispatcherType.ERROR) {
            request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/test/original");
        }
        var nextResponse = new MockHttpServletResponse();
        try (var executor = Executors.newSingleThreadExecutor()) {
            executor.submit(() -> {
                try {
                    filter.doFilter(request, nextResponse, (ignoredRequest, ignoredResponse) ->
                            assertEquals(expectedId, MDC.get(RequestIdFilter.MDC_KEY)));
                    assertNull(MDC.get(RequestIdFilter.MDC_KEY));
                    return null;
                } finally {
                    MDC.clear();
                }
            }).get(10, TimeUnit.SECONDS);
        }
        assertEquals(expectedId, nextResponse.getHeader(RequestIdFilter.HEADER_NAME));
    }

    /** Error dispatch lồng nhau khôi phục header đã reset mà không phá MDC của lượt bên ngoài. */
    @Test
    void preservesIdDuringNestedErrorDispatch() throws Exception {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
            String expectedId = MDC.get(RequestIdFilter.MDC_KEY);
            response.reset();
            request.setDispatcherType(DispatcherType.ERROR);
            request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/test/original");
            filter.doFilter(request, response, (nestedRequest, nestedResponse) -> {
                assertEquals(expectedId, MDC.get(RequestIdFilter.MDC_KEY));
                assertEquals(expectedId, response.getHeader(RequestIdFilter.HEADER_NAME));
            });
            assertEquals(expectedId, MDC.get(RequestIdFilter.MDC_KEY));
        });
        assertNull(MDC.get(RequestIdFilter.MDC_KEY));
    }

    /** Hai chain thực sự cùng hoạt động vẫn thấy ID riêng trước và sau điểm đồng bộ. */
    @Test
    void isolatesConcurrentRequests() throws Exception {
        var entered = new CountDownLatch(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> runConcurrentRequest("client-first", entered));
            var second = executor.submit(() -> runConcurrentRequest("client-second", entered));
            assertEquals("client-first", first.get(10, TimeUnit.SECONDS));
            assertEquals("client-second", second.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    /** Chạy một request trên worker, kiểm MDC trong chain và sau khi filter trả về. */
    private String runConcurrentRequest(String requestId, CountDownLatch entered) throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER_NAME, requestId);
        var response = new MockHttpServletResponse();
        try {
            assertNull(MDC.get(RequestIdFilter.MDC_KEY));
            filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
                assertEquals(requestId, MDC.get(RequestIdFilter.MDC_KEY));
                entered.countDown();
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS), "Both requests must enter concurrently.");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while coordinating requests.", exception);
                }
                assertEquals(requestId, MDC.get(RequestIdFilter.MDC_KEY));
            });
            assertNull(MDC.get(RequestIdFilter.MDC_KEY));
            return response.getHeader(RequestIdFilter.HEADER_NAME);
        } finally {
            MDC.clear();
        }
    }

    /** Kiểm ID server sinh là UUID chuẩn và thuộc tập ký tự được công bố. */
    private static void assertGeneratedId(String value) {
        assertNotNull(value);
        assertTrue(value.matches("[A-Za-z0-9-]{1,64}"));
        assertEquals(value, UUID.fromString(value).toString());
    }
}
