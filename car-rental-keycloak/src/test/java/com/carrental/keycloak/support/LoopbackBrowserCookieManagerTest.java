package com.carrental.keycloak.support;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Khoá hành vi cookie của browser test; toàn bộ là xử lý trong bộ nhớ, không gọi HTTP. */
class LoopbackBrowserCookieManagerTest {
    private static final URI ORIGIN = URI.create("http://localhost:48123");
    private static final URI ACTION = ORIGIN.resolve("/realms/car-rental/login-actions/authenticate");
    private static final Map<String, List<String>> RESPONSE = Map.of("Set-Cookie", List.of(
            "AUTH_SESSION_ID=fake-session; Version=1; Path=/realms/car-rental/; Secure; HttpOnly; SameSite=Lax"));

    /** Tái hiện nguyên nhân: CookieManager mặc định không gửi Secure cookie khi form ở HTTP localhost. */
    @Test void standardJavaCookieManagerDropsSecureCookieOnHttpLoopback() throws Exception {
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
        cookies.put(ACTION, RESPONSE);
        assertEquals(1, cookies.getCookieStore().getCookies().size());
        assertFalse(hasSession(cookies.get(ACTION, Map.of())));
    }

    /** Helper gửi lại cookie đúng origin nhưng giữ nguyên cờ Secure và HttpOnly nhận từ server. */
    @Test void sendsOnExactLoopbackOriginWithoutRemovingSecurityFlags() throws Exception {
        var cookies = new LoopbackBrowserCookieManager(ORIGIN);
        cookies.put(ACTION, RESPONSE);
        assertTrue(hasSession(cookies.get(ACTION, Map.of())));
        var stored = cookies.getCookieStore().getCookies().getFirst();
        assertTrue(stored.getSecure());
        assertTrue(stored.isHttpOnly());
    }

    /** Ngoại lệ không lan sang path, cổng hay hostname khác với phạm vi đã chọn. */
    @Test void doesNotSendOutsidePathOrToAnotherHttpOrigin() throws Exception {
        var cookies = new LoopbackBrowserCookieManager(ORIGIN);
        cookies.put(ACTION, RESPONSE);
        assertFalse(hasSession(cookies.get(ORIGIN.resolve("/outside"), Map.of())));
        assertFalse(hasSession(cookies.get(URI.create("http://localhost:48124/realms/car-rental/"), Map.of())));
        assertFalse(hasSession(cookies.get(URI.create("http://example.invalid:48123/realms/car-rental/"), Map.of())));
    }

    /** Server xoá cookie thì helper không hồi sinh phiên đã hết hạn. */
    @Test void respectsServerCookieDeletion() throws Exception {
        var cookies = new LoopbackBrowserCookieManager(ORIGIN);
        cookies.put(ACTION, RESPONSE);
        cookies.put(ACTION, Map.of("Set-Cookie", List.of(
                "AUTH_SESSION_ID=; Version=1; Path=/realms/car-rental/; Max-Age=0; Secure; HttpOnly")));
        assertFalse(hasSession(cookies.get(ACTION, Map.of())));
    }

    /** Kể cả origin được cấu hình, HTTP ngoài loopback không được nhận ngoại lệ Secure. */
    @Test void doesNotRelaxSecureCookiesForNonLoopbackHosts() throws Exception {
        URI remote = URI.create("http://example.invalid:48123/realms/car-rental/");
        var cookies = new LoopbackBrowserCookieManager(remote);
        cookies.put(remote, RESPONSE);
        assertFalse(hasSession(cookies.get(remote, Map.of())));
    }

    /** Chỉ xét tên cookie giả, không phụ thuộc cách JDK đặt dấu nháy cho giá trị version 1. */
    private boolean hasSession(Map<String, List<String>> headers) {
        return headers.values().stream().flatMap(List::stream).anyMatch(value -> value.contains("AUTH_SESSION_ID="));
    }
}
