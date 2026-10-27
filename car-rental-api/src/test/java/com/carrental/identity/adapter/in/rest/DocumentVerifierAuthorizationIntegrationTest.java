package com.carrental.identity.adapter.in.rest;

import com.carrental.PostgresTestConfiguration;
import com.carrental.branch.application.port.out.WriteBranchPort;
import com.carrental.vehicle.application.port.out.WriteVehiclePort;
import com.carrental.identity.application.port.out.WriteStaffBranchAssignmentPort;
import com.carrental.identity.config.HttpIdentityTestConfiguration;
import com.carrental.identity.config.HttpIdentityTestConfiguration.TestJwtIssuer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** BR-130/803/810: JWT thật đi qua decoder/caller/use case/SQL, verifier không có quyền vận hành. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestConfiguration.class, HttpIdentityTestConfiguration.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DocumentVerifierAuthorizationIntegrationTest {
    @LocalServerPort int port;
    @Autowired TestJwtIssuer tokens;
    @Autowired Clock clock;
    @Autowired ObjectMapper json;
    @MockitoSpyBean WriteBranchPort branches;
    @MockitoSpyBean WriteVehiclePort vehicles;
    @MockitoSpyBean WriteStaffBranchAssignmentPort assignments;
    private String verifier;
    private String branch;
    private String vehicle;
    private String account;
    private Map<String, Object> branchBody;
    private Map<String, Object> vehicleBody;

    /** ADMIN tạo tài nguyên thật trước để 403 không bị nhầm với lỗi mã không tồn tại. */
    @BeforeEach void prepareResources() throws Exception {
        String admin = tokens.adminToken(clock);
        verifier = tokens.documentVerifierToken(clock);
        branchBody = Map.of("latitude", 10.76, "longitude", 106.66, "name", "Local verifier " + UUID.randomUUID(),
                "address", "Local test address");
        var created = send(admin, "POST", "/api/v1/admin/branches", branchBody);
        assertEquals(201, created.statusCode()); branch = json.readTree(created.body()).path("data").path("code").asString();
        vehicleBody = Map.of("plateNumber", "VERIFIER-" + UUID.randomUUID(), "branchCode", branch,
                "fuelType", "PETROL", "seats", 4, "transmission", "AUTOMATIC", "make", "Local", "model", "Verifier");
        created = send(admin, "POST", "/api/v1/admin/vehicles", vehicleBody);
        assertEquals(201, created.statusCode()); vehicle = json.readTree(created.body()).path("data").path("code").asString();
        var me = send(verifier, "GET", "/api/v1/admin/accounts/me", null);
        assertEquals(200, me.statusCode()); account = json.readTree(me.body()).path("data").path("code").asString();
        clearInvocations(branches, vehicles, assignments);
    }

    /** Thao tác bị từ chối không được ghi rồi mới trả lỗi. */
    @AfterEach void verifiesNoBusinessWrites() { verifyNoInteractions(branches, vehicles, assignments); }

    /** Vai được nhận diện là nhân viên, không cần bản gán và không tự được thêm ADMIN/CUSTOMER. */
    @Test void readsOwnAccountWithoutBranchOrExtraRoles() throws Exception {
        var response = send(verifier, "GET", "/api/v1/admin/accounts/me", null);
        assertEquals(200, response.statusCode());
        var data = json.readTree(response.body()).path("data");
        assertEquals(1, data.path("roles").size());
        assertEquals("DOCUMENT_VERIFIER", data.path("roles").get(0).asString());
        assertTrue(data.path("branchCode").isNull());
        assertTrue(data.path("termsVersion").isNull());
        assertTrue(data.path("code").asString().matches("TK-[A-Z0-9]{6}"));
    }

    /** Mọi thao tác hiện có trên xe/chi nhánh/gán người đều bị use case từ chối đúng 403. */
    @ParameterizedTest @ValueSource(strings = {"createBranch", "readBranch", "createVehicle", "readVehicle",
            "price", "submit", "approve", "assignBranch", "clientDocuments"})
    void rejectsUnrelatedOperations(String operation) throws Exception {
        var response = switch (operation) {
            case "createBranch" -> send(verifier, "POST", "/api/v1/admin/branches", branchBody);
            case "readBranch" -> send(verifier, "GET", "/api/v1/admin/branches/" + branch, null);
            case "createVehicle" -> send(verifier, "POST", "/api/v1/admin/vehicles", vehicleBody);
            case "readVehicle" -> send(verifier, "GET", "/api/v1/admin/vehicles/" + vehicle, null);
            case "price" -> send(verifier, "PUT", "/api/v1/admin/vehicles/" + vehicle + "/daily-rate", Map.of("dailyRate", 600000));
            case "submit" -> send(verifier, "POST", "/api/v1/admin/vehicles/" + vehicle + "/submit-for-approval", null);
            case "approve" -> send(verifier, "POST", "/api/v1/admin/vehicles/" + vehicle + "/approve", null);
            case "assignBranch" -> send(verifier, "PUT", "/api/v1/admin/accounts/" + account + "/branch", Map.of("branchCode", branch));
            case "clientDocuments" -> send(verifier, "GET", "/api/v1/client/documents", null);
            default -> throw new IllegalArgumentException("Unknown test operation.");
        };
        assertEquals(403, response.statusCode());
        var body = json.readTree(response.body());
        assertFalse(body.path("success").asBoolean()); assertTrue(body.path("data").isNull());
        assertEquals("ACCESS_DENIED", body.path("error").path("code").asString());
    }

    /** HTTP thật, không log bearer; body hợp lệ để không nhầm lỗi validation với kiểm quyền. */
    private HttpResponse<String> send(String bearer, String method, String path, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + bearer)
                .header("Content-Type", "application/json").header("X-Client-Platform", "web")
                .header("X-Client-Version", "0.0.1").method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        try (var http = HttpClient.newHttpClient()) { return http.send(request.build(), HttpResponse.BodyHandlers.ofString()); }
    }
}
