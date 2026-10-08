package com.carrental.shared.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Enumeration;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Gắn mã truy vết an toàn cho mỗi request theo security-guideline §3.
 * Không ghi header đầu vào, body, token hoặc query string vào log.
 * Mã được giữ qua error/async dispatch; MDC chỉ tồn tại trong lượt xử lý của thread hiện tại.
 */
public final class RequestIdFilter extends OncePerRequestFilter {
    public static final String HEADER_NAME = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    private static final String REQUEST_ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    /** Tạo filter không giữ trạng thái riêng của bất kỳ request nào. */
    public RequestIdFilter() {
    }

    /** Gắn ID vào response và MDC, luôn khôi phục MDC dù chain ném exception. */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = resolveRequestId(request);
        String previousId = MDC.get(MDC_KEY);
        MDC.put(MDC_KEY, requestId);
        try {
            if (!response.isCommitted()) {
                response.setHeader(HEADER_NAME, requestId);
            }
            filterChain.doFilter(request, response);
        } finally {
            if (previousId == null) {
                MDC.remove(MDC_KEY);
            } else {
                MDC.put(MDC_KEY, previousId);
            }
        }
    }

    /** Nạp lại cùng ID khi container tiếp tục request bất đồng bộ trên một thread khác. */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    /** Giữ mã truy vết khi container chuyển sang xử lý lỗi. */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    /** Khôi phục cả response header nếu error dispatch lồng nhau đã reset response. */
    @Override
    protected void doFilterNestedErrorDispatch(HttpServletRequest request, HttpServletResponse response,
                                               FilterChain filterChain) throws ServletException, IOException {
        doFilterInternal(request, response, filterChain);
    }

    /** Ưu tiên ID đã chọn trên request; chỉ nhận đúng một header có toàn bộ ký tự hợp lệ. */
    private String resolveRequestId(HttpServletRequest request) {
        Object existing = request.getAttribute(REQUEST_ATTRIBUTE);
        if (existing instanceof String requestId) {
            return requestId;
        }
        Enumeration<String> headers = request.getHeaders(HEADER_NAME);
        String candidate = headers != null && headers.hasMoreElements() ? headers.nextElement() : null;
        boolean singleHeader = headers == null || !headers.hasMoreElements();
        String requestId = singleHeader && candidate != null && SAFE_ID.matcher(candidate).matches()
                ? candidate : UUID.randomUUID().toString();
        request.setAttribute(REQUEST_ATTRIBUTE, requestId);
        return requestId;
    }
}
