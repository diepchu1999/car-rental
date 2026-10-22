package com.carrental.identity.adapter.in.internal;

import com.carrental.identity.api.CallerDirectory;
import com.carrental.identity.api.CallerRef;
import com.carrental.shared.error.DomainException;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Đọc danh tính từ thuộc tính phía máy chủ của đúng request hiện tại, không giữ trạng thái trên bean.
 * Biên xác thực phải đặt thuộc tính CallerRef.class.getName() sau khi đã kiểm token và đồng bộ
 * tài khoản/phạm vi. Không có thuộc tính thì từ chối; không suy danh tính từ header hoặc tham số.
 */
@Component
class HttpCallerDirectory implements CallerDirectory {
    /** Dựng adapter không có trạng thái người gọi dùng chung giữa các thread. */
    HttpCallerDirectory() { }

    /** Phân biệt thiếu request, chưa xác thực và dữ liệu nội bộ bị đặt sai kiểu. */
    @Override
    public CallerRef current() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes context)) {
            throw new IllegalStateException("CallerDirectory requires an active HTTP request.");
        }
        Object caller = context.getRequest().getAttribute(CallerRef.class.getName());
        if (caller == null) throw DomainException.unauthenticated();
        if (!(caller instanceof CallerRef authenticated)) {
            throw new IllegalStateException("The authenticated caller request attribute has an invalid type.");
        }
        return authenticated;
    }
}
