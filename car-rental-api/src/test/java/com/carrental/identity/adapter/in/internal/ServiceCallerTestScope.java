package com.carrental.identity.adapter.in.internal;

import com.carrental.identity.api.CallerRef;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Ngữ cảnh người gọi tường minh cho test gọi trực tiếp use case (BR-803/810).
 * Chỉ mô phỏng đầu ra của biên xác thực; không chứng minh JWT và không dùng để gửi request HTTP.
 * Không đăng ký bean, không thay CallerDirectory/Security; adapter production vẫn kiểm thuộc tính request.
 * Phạm vi chỉ ở thread hiện tại, không mở transaction và luôn khôi phục ngữ cảnh trước đó khi đóng.
 */
public final class ServiceCallerTestScope implements AutoCloseable {
    private final Thread owner = Thread.currentThread();
    private final RequestAttributes previous;
    private final ServletRequestAttributes current;
    private boolean closed;

    /** Tạo request riêng cho danh tính đã chọn, không kế thừa sang thread con. */
    private ServiceCallerTestScope(CallerRef caller) {
        Objects.requireNonNull(caller, "caller must not be null");
        previous = RequestContextHolder.getRequestAttributes();
        var request = new MockHttpServletRequest();
        request.setAttribute(CallerRef.class.getName(), caller);
        current = new ServletRequestAttributes(request);
        RequestContextHolder.setRequestAttributes(current, false);
    }

    /** Mở phạm vi đúng danh tính mà test chỉ định; không có vai mặc định khi thiếu tham số. */
    public static ServiceCallerTestScope open(CallerRef caller) {
        return new ServiceCallerTestScope(caller);
    }

    /** Chọn ADMIN tường minh để dựng dữ liệu thử, không dùng chung tự động cho mọi test. */
    public static ServiceCallerTestScope admin() {
        return open(new CallerRef("TK-TEST01", Set.of("ADMIN"), null));
    }

    /** Dựng fixture qua use case thật rồi thu hồi ADMIN, kể cả khi callback ném lỗi. */
    public static <T> T asAdmin(Supplier<T> action) {
        Objects.requireNonNull(action, "action must not be null");
        try (var scope = admin()) {
            return action.get();
        }
    }

    /** Đóng đúng thứ tự trên thread đã mở; không để caller lọt sang test tiếp theo. */
    @Override
    public void close() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("Caller test scope must close on its owning thread.");
        }
        if (closed) return;
        if (RequestContextHolder.getRequestAttributes() != current) {
            throw new IllegalStateException("Caller test scopes must close in reverse opening order.");
        }
        closed = true;
        try {
            current.getRequest().removeAttribute(CallerRef.class.getName());
            current.requestCompleted();
        } finally {
            RequestContextHolder.setRequestAttributes(previous, false);
        }
    }
}
