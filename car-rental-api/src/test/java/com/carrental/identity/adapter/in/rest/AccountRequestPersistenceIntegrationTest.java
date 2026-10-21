package com.carrental.identity.adapter.in.rest;

import com.carrental.PostgresTestConfiguration;
import com.carrental.identity.application.port.out.WriteAccountPort;
import com.carrental.identity.config.HttpIdentityTestConfiguration;
import com.carrental.identity.config.HttpIdentityTestConfiguration.TestJwtIssuer;
import org.junit.jupiter.api.Test;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** L1, BR-120/809: ba HTTP request dùng cùng JWT thật, filter/use case/adapter và PostgreSQL thật. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestConfiguration.class, HttpIdentityTestConfiguration.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AccountRequestPersistenceIntegrationTest {
    @LocalServerPort private int port;
    @Autowired private TestJwtIssuer tokens;
    @Autowired private Clock clock;
    @Autowired private ObjectMapper json;
    @MockitoSpyBean private WriteAccountPort writes;

    /** Spy không thay hành vi ghi: lần đầu INSERT, hai request sau chỉ đọc và giữ nguyên mã/consent. */
    @Test
    void threeRequestsWithSameTokenInsertOnlyOnce() throws Exception {
        String token = tokens.customerToken(clock);
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/client/accounts/me"))
                .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + token).GET().build();
        String firstCode = null;
        clearInvocations(writes);
        try (var http = HttpClient.newHttpClient()) {
            for (int attempt = 0; attempt < 3; attempt++) {
                var response = http.send(request, HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
                var body = json.readTree(response.body());
                assertTrue(body.path("success").asBoolean());
                String code = body.path("data").path("code").asString();
                assertTrue(code.matches("TK-[A-Z0-9]{6}"));
                if (firstCode == null) firstCode = code;
                assertEquals(firstCode, code);
                assertEquals("http-test-terms-v1", body.path("data").path("termsVersion").asString());
                verify(writes, times(1)).insert(any(), any());
                verify(writes, never()).addConsentIfAbsent(any());
            }
        }
    }
}
