package com.carrental.keycloak.probe;

import com.carrental.keycloak.otp.OtpProvider;
import com.carrental.keycloak.otp.OtpScope;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.Provider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.common.util.Time;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Điểm vào thử nằm hoàn toàn trong src/test; không cấp quyền, không cấp token, không đi vào compose. */
@Provider
@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
@Produces(MediaType.TEXT_PLAIN)
public final class OtpProbeResource {
    private final KeycloakSession session;
    /** Giữ session HTTP thật để chứng minh transaction con độc lập với transaction cha. */
    public OtpProbeResource(KeycloakSession session) { this.session = session; }

    /** Gọi đường phát OTP thật; IP và authentication-session ở đây là fixture, không phải input API thật. */
    @POST @Path("issue")
    public String issue(@HeaderParam("X-Test-Probe") String secret,
                        @FormParam("session") String authenticationSession, @FormParam("phone") String phone,
                        @FormParam("purpose") String purpose, @FormParam("ip") String ip,
                        @FormParam("rollback") boolean rollback) {
        authorize(secret);
        var result = session.getProvider(OtpProvider.class).issue(scope(authenticationSession, phone, purpose), ip);
        if (rollback) session.getTransactionManager().setRollbackOnly();
        return result.result() + "|" + (result.challengeId() == null ? "" : result.challengeId());
    }

    /** Kết quả OTP phải sống qua cả rollback ở vòng đăng nhập ngoài. */
    @POST @Path("verify")
    public String verify(@HeaderParam("X-Test-Probe") String secret,
                         @FormParam("session") String authenticationSession, @FormParam("phone") String phone,
                         @FormParam("purpose") String purpose, @FormParam("ip") String ip,
                         @FormParam("id") String id, @FormParam("code") String code,
                         @FormParam("rollback") boolean rollback) {
        authorize(secret);
        var result = session.getProvider(OtpProvider.class).verify(scope(authenticationSession, phone, purpose), ip, id, code);
        if (rollback) session.getTransactionManager().setRollbackOnly();
        return result.name();
    }

    /** Realm lấy từ Keycloak, không cho request truyền realm tuỳ ý. */
    private OtpScope scope(String authenticationSession, String phone, String purpose) {
        return new OtpScope(session.getContext().getRealm().getId(), authenticationSession, phone,
                OtpScope.Purpose.valueOf(purpose));
    }

    /**
     * Chỉ dịch mốc của bản ghi đã có trong container test, giữ nguyên số lần sai và policy realm.
     * Cho phép thử đủ 25 mật khẩu sai và cửa sổ 12 giờ mà không chờ hàng giờ ngoài đời thực.
     * Không cấp token, không sinh lượt sai giả, không nằm trong JAR provider triển khai.
     */
    @POST @Path("age-password-failure")
    public String agePasswordFailure(@HeaderParam("X-Test-Probe") String secret,
                                     @FormParam("userId") String userId,
                                     @FormParam("ageSeconds") int ageSeconds) {
        authorize(secret);
        if (ageSeconds < 2 || ageSeconds > 43201) throw new IllegalArgumentException("Invalid test failure age.");
        var realm = session.getContext().getRealm();
        var failure = session.loginFailures().getUserLoginFailure(realm, userId);
        if (failure == null) throw new IllegalStateException("Expected an existing password failure.");

        // Keycloak 26.7.2 chỉ cho hai setter thời gian tăng, nên không thể lùi trực tiếp.
        // Chụp trước khi clear; phục hồi bộ đếm trong cùng transaction, không thêm lần sai mới.
        int failures = failure.getNumFailures();
        int temporaryLockouts = failure.getNumTemporaryLockouts();
        String lastIp = failure.getLastIPFailure();
        long lastFailure = Math.min(Time.currentTimeMillis(), failure.getLastFailure()) - ageSeconds * 1000L;
        if (failures < 1 || lastFailure <= 0) throw new IllegalStateException("Expected a recorded password failure.");
        // clearFailures không xoá numSecondaryAuthFailures; không được increment lại bộ đếm này.
        failure.clearFailures();
        for (int i = 0; i < failures; i++) failure.incrementFailures();
        for (int i = 0; i < temporaryLockouts; i++) failure.incrementTemporaryLockouts();
        failure.setLastIPFailure(lastIp);
        failure.setLastFailure(lastFailure);
        failure.setFailedLoginNotBefore(Math.toIntExact(Time.currentTimeSeconds() - 1));
        return "aged|" + lastFailure;
    }

    /** Fixture chỉ hoạt động với secret ngẫu nhiên do test giữ, ngay cả trong container cô lập. */
    private void authorize(String actual) {
        String expected = System.getenv("OTP_TEST_PROBE_SECRET");
        if (expected == null || actual == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8))) throw new ForbiddenException();
    }
}
