package com.carrental.keycloak.authentication;

import com.carrental.keycloak.otp.OtpProvider;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import java.util.HashMap;
import java.util.Map;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.common.ClientConnection;
import org.keycloak.events.EventBuilder;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.http.HttpRequest;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.SubjectCredentialManager;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserProvider;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.keycloak.sessions.RootAuthenticationSessionModel;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Phiên Keycloak giả cho unit test: auth note phía máy chủ tách hẳn dữ liệu form do client gửi. */
final class EmployeeFlowFixture {
    final AuthenticationFlowContext context = mock(AuthenticationFlowContext.class);
    final AuthenticationSessionModel auth = mock(AuthenticationSessionModel.class);
    final RootAuthenticationSessionModel root = mock(RootAuthenticationSessionModel.class);
    final KeycloakSession session = mock(KeycloakSession.class);
    final RealmModel realm = mock(RealmModel.class);
    final ClientModel client = mock(ClientModel.class);
    final UserModel user = mock(UserModel.class);
    final UserProvider users = mock(UserProvider.class);
    final SubjectCredentialManager credentials = mock(SubjectCredentialManager.class);
    final RoleModel employeeRole = mock(RoleModel.class);
    final OtpProvider otp = mock(OtpProvider.class);
    final HttpRequest request = mock(HttpRequest.class);
    final LoginFormsProvider forms = mock(LoginFormsProvider.class, RETURNS_SELF);
    final Response response = mock(Response.class);
    final Map<String, String> notes = new HashMap<>();
    final MultivaluedHashMap<String, String> input = new MultivaluedHashMap<>();
    private UserModel currentUser;

    /** Số/IP và mật khẩu đều giả; không đọc .env, không cần container cho kiểm luồng này. */
    EmployeeFlowFixture() {
        when(context.getAuthenticationSession()).thenReturn(auth);
        when(context.getRealm()).thenReturn(realm);
        when(context.getSession()).thenReturn(session);
        when(context.getHttpRequest()).thenReturn(request);
        when(context.form()).thenReturn(forms);
        when(context.getUser()).thenAnswer(call -> currentUser);
        doAnswer(call -> { currentUser = call.getArgument(0); return null; }).when(context).setUser(any(UserModel.class));
        doAnswer(call -> { currentUser = null; return null; }).when(context).clearUser();
        when(context.getEvent()).thenReturn(mock(EventBuilder.class, RETURNS_SELF));
        var execution = new AuthenticationExecutionModel();
        execution.setId("password-execution");
        when(context.getExecution()).thenReturn(execution);
        var connection = mock(ClientConnection.class);
        when(context.getConnection()).thenReturn(connection);
        when(connection.getRemoteAddr()).thenReturn("192.0.2.10");
        when(auth.getParentSession()).thenReturn(root);
        when(root.getId()).thenReturn("root-session");
        when(auth.getClient()).thenReturn(client);
        when(client.getId()).thenReturn("admin-client-id");
        when(auth.getTabId()).thenReturn("login-tab");
        when(realm.getId()).thenReturn("car-rental-realm");
        when(realm.getRole("BRANCH_STAFF")).thenReturn(employeeRole);
        when(user.hasRole(employeeRole)).thenReturn(true);
        when(user.isEnabled()).thenReturn(true);
        when(user.getId()).thenReturn("employee-id");
        when(user.getUsername()).thenReturn("+84912345678");
        when(user.credentialManager()).thenReturn(credentials);
        when(session.users()).thenReturn(users);
        when(users.getUserByUsername(realm, "+84912345678")).thenReturn(user);
        when(session.getProvider(OtpProvider.class)).thenReturn(otp);
        when(auth.getAuthNote(anyString())).thenAnswer(call -> notes.get(call.getArgument(0)));
        doAnswer(call -> { notes.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(auth).setAuthNote(anyString(), anyString());
        doAnswer(call -> { notes.remove(call.getArgument(0)); return null; }).when(auth).removeAuthNote(anyString());
        when(request.getDecodedFormParameters()).thenReturn(input);
        when(forms.createForm(anyString())).thenReturn(response);
        when(forms.createLoginUsernamePassword()).thenReturn(response);
        when(forms.createErrorPage(any(Response.Status.class))).thenReturn(response);
    }

    /** Đặt nhân viên vào context mà không xác minh mật khẩu, dùng để thử chặn đường tắt SSO. */
    void identifyEmployee() { currentUser = user; }

    /** Mô phỏng dấu do bước mật khẩu vừa hoàn tất, không đặt qua form phía client. */
    void passwordVerified() {
        identifyEmployee();
        notes.put(EmployeeLoginState.PASSWORD_VERIFIED, EmployeeLoginState.binding(context));
    }

    /** Chuẩn bị form nhập mã cùng challenge do máy chủ đã phát trước đó. */
    void verificationForm() {
        passwordVerified();
        notes.put(EmployeeLoginState.CHALLENGE_ID, "server-challenge");
        input.putSingle("action", "verify");
        input.putSingle("otp", "123456");
    }

    /** Xác nhận chưa có nhánh nào báo thành công, kể cả overload kèm loại credential. */
    void assertNoSuccess() {
        verify(context, never()).success();
        verify(context, never()).success(anyString());
    }
}
