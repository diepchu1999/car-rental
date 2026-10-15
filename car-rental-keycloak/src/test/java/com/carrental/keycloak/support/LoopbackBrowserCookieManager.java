package com.carrental.keycloak.support;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;

/** Mô phỏng cookie Secure trên localhost như trình duyệt; chỉ dùng cho origin container test đã chọn. */
final class LoopbackBrowserCookieManager extends CookieManager {
    private final URI origin;

    /** Không sửa cookie server, không tắt Secure trong realm và không dùng DNS để mở rộng ngoại lệ. */
    LoopbackBrowserCookieManager(URI origin) {
        super(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
        this.origin = origin;
    }

    /**
     * Keycloak coi HTTP loopback là secure context nhưng CookieManager chỉ biết scheme HTTPS.
     * Chỉ đổi scheme khi CHỌN cookie cho đúng origin HTTP loopback; request mạng vẫn giữ URI gốc.
     * CookieManager tiếp tục kiểm domain, path, hạn dùng và HttpOnly; cờ Secure trong kho không bị xoá.
     */
    @Override public Map<String, List<String>> get(URI uri, Map<String, List<String>> headers) throws IOException {
        if (uri != null && "http".equals(uri.getScheme()) && "http".equals(origin.getScheme())
                && origin.getAuthority().equals(uri.getAuthority()) && isLiteralLoopback(uri.getHost())) {
            try {
                URI selectionUri = new URI("https", uri.getUserInfo(), uri.getHost(), uri.getPort(),
                        uri.getPath(), uri.getQuery(), uri.getFragment());
                return super.get(selectionUri, headers);
            } catch (URISyntaxException invalid) {
                throw new IOException("Cannot select cookies for the loopback test origin.", invalid);
            }
        }
        return super.get(uri, headers);
    }

    /** Danh sách literal hữu hạn: không chấp nhận hostname từ DNS hoặc địa chỉ máy khác. */
    private boolean isLiteralLoopback(String host) {
        return "localhost".equals(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
    }
}
