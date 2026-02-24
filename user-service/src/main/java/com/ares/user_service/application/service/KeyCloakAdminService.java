package com.ares.user_service.application.service;

import com.ares.user_service.api.request.CreateUserRequest;
import com.ares.user_service.common.exception.ErrorCode;
import com.ares.user_service.common.exception.SystemException;
import com.ares.user_service.domain.model.User;
import com.ares.user_service.domain.model.UserStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@Slf4j
public class KeyCloakAdminService {

    @Value("${keycloak.admin.server-url}")
    private String keycloakServerUrl;

    @Value("${keycloak.admin.realm}")
    private String realm;

    @Value("${keycloak.admin.client-id}")
    private String clientId;

    @Value("${keycloak.admin.client-secret}")
    private String clientSecret;

    @Value("${keycloak.admin.client-uid}")
    private String clientUid;

    private final RestTemplate restTemplate = new RestTemplate();


    public String getAdminAccessToken() {

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("client_id", clientId);
        params.add("client_secret", clientSecret);
        params.add("grant_type", "client_credentials");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        HttpEntity<MultiValueMap<String, String>> entity =
                new HttpEntity<>(params, headers);

        String url = keycloakServerUrl +
                "/realms/" + realm +
                "/protocol/openid-connect/token";

        ResponseEntity<Map> response =
                restTemplate.postForEntity(url, entity, Map.class);

        return (String) response.getBody().get("access_token");
    }


    public String createUser(String token, CreateUserRequest request) {

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);

        Map<String, Object> userPayload = new HashMap<>();
        userPayload.put("username", request.username());
        userPayload.put("email", request.email());
        userPayload.put("enabled", true);
        userPayload.put("firstName", request.firstName());
        userPayload.put("lastName", request.lastName());

        Map<String, Object> credential = new HashMap<>();
        credential.put("type", "password");
        credential.put("value", request.password());
        credential.put("temporary", false);

        userPayload.put("credentials", List.of(credential));

        HttpEntity<Map<String, Object>> entity =
                new HttpEntity<>(userPayload, headers);

        String url = keycloakServerUrl +
                "/admin/realms/" + realm +
                "/users";

        ResponseEntity<String> response =
                restTemplate.postForEntity(url, entity, String.class);

        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("Failed to create user in Keycloak");
        }

        URI location = response.getHeaders().getLocation();
        if (location == null) {
            throw new RuntimeException("Missing Location header from Keycloak");
        }

        return location.getPath()
                .substring(location.getPath().lastIndexOf("/") + 1);
    }



    private Map<String, Object> getClientRoleRepresentation(
            String token,
            String roleName
    ) {

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        String url = keycloakServerUrl +
                "/admin/realms/" + realm +
                "/roles/" + roleName;

        try {

            ResponseEntity<Map<String, Object>> response =
                    restTemplate.exchange(
                            url,
                            HttpMethod.GET,
                            entity,
                            new ParameterizedTypeReference<>() {}
                    );

            return response.getBody();

        } catch (HttpStatusCodeException ex) {

            //  LOG FULL RESPONSE BODY
            log.error("Keycloak error: status={}, body={}",
                    ex.getStatusCode(),
                    ex.getResponseBodyAsString()
            );

            throw new SystemException(
                    ErrorCode.KEYCLOAK_PROVISION_FAILED
            );
        }
    }


    public void assignRealmRoles(
            String token,
            String userId,
            List<String> roleNames
    ) {

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);

        List<Map<String, Object>> roleRepresentations =
                roleNames.stream()
                        .map(role ->
                                getClientRoleRepresentation(token, role)
                        )
                        .toList();

        HttpEntity<List<Map<String, Object>>> entity =
                new HttpEntity<>(roleRepresentations, headers);

        String url = keycloakServerUrl +
                "/admin/realms/" + realm +
                "/users/" + userId +
                "/role-mappings/realm";

        ResponseEntity<Void> response =
                restTemplate.postForEntity(url, entity, Void.class);

        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("Assign role failed");
        }
    }



    public void deleteUser(String token, String userId) {

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        String url = keycloakServerUrl +
                "/admin/realms/" + realm +
                "/users/" + userId;

        restTemplate.exchange(
                url,
                HttpMethod.DELETE,
                entity,
                Void.class
        );
    }

    public void updateUser(String token, String userId, User user) {

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);

        Map<String, Object> userPayload = new HashMap<>();
        userPayload.put("username", user.getUsername());
        userPayload.put("email", user.getEmail());
        userPayload.put("firstName", user.getFirstName());
        userPayload.put("lastName", user.getLastName());

        if (user.getStatus() != null) {
            userPayload.put("enabled", user.getStatus() == UserStatus.ACTIVE);
        }

        HttpEntity<Map<String, Object>> entity =
                new HttpEntity<>(userPayload, headers);

        String url = keycloakServerUrl +
                "/admin/realms/" + realm +
                "/users/" + userId;

        ResponseEntity<Void> response =
                restTemplate.exchange(
                        url,
                        HttpMethod.PUT,
                        entity,
                        Void.class
                );

        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new SystemException(ErrorCode.KEYCLOAK_PROVISION_FAILED);
        }
    }

    public void resetPassword(String token, String userId, String newPassword, boolean temporary) {

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);

        Map<String, Object> credential = new HashMap<>();
        credential.put("type", "password");
        credential.put("value", newPassword);
        credential.put("temporary", temporary);

        HttpEntity<Map<String, Object>> entity =
                new HttpEntity<>(credential, headers);

        String url = keycloakServerUrl +
                "/admin/realms/" + realm +
                "/users/" + userId +
                "/reset-password";

        ResponseEntity<Void> response =
                restTemplate.exchange(
                        url,
                        HttpMethod.PUT,
                        entity,
                        Void.class
                );

        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new SystemException(ErrorCode.KEYCLOAK_PROVISION_FAILED);
        }
    }

    public List<String> getUserRealmRoles(String token, String userId) {

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        String url = keycloakServerUrl +
                "/admin/realms/" + realm +
                "/users/" + userId +
                "/role-mappings/realm";

        ResponseEntity<List<Map<String, Object>>> response =
                restTemplate.exchange(
                        url,
                        HttpMethod.GET,
                        entity,
                        new ParameterizedTypeReference<>() {}
                );

        List<Map<String, Object>> body = response.getBody();
        if (body == null) {
            return List.of();
        }

        return body.stream()
                .map(role -> (String) role.get("name"))
                .filter(Objects::nonNull)
                .toList();
    }

    public void removeRealmRoles(String token, String userId, List<String> roleNames) {

        if (roleNames == null || roleNames.isEmpty()) {
            return;
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);

        List<Map<String, Object>> roleRepresentations =
                roleNames.stream()
                        .map(role -> getClientRoleRepresentation(token, role))
                        .toList();

        HttpEntity<List<Map<String, Object>>> entity =
                new HttpEntity<>(roleRepresentations, headers);

        String url = keycloakServerUrl +
                "/admin/realms/" + realm +
                "/users/" + userId +
                "/role-mappings/realm";

        ResponseEntity<Void> response =
                restTemplate.exchange(
                        url,
                        HttpMethod.DELETE,
                        entity,
                        Void.class
                );

        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new SystemException(ErrorCode.KEYCLOAK_PROVISION_FAILED);
        }
    }

    public void syncRealmRoles(String token, String userId, List<String> desiredRoles) {

        List<String> existingRoles = getUserRealmRoles(token, userId);
        Set<String> desiredSet = new HashSet<>(desiredRoles == null ? List.of() : desiredRoles);
        Set<String> existingSet = new HashSet<>(existingRoles);

        Set<String> toRemove = new HashSet<>(existingSet);
        toRemove.removeAll(desiredSet);

        Set<String> toAdd = new HashSet<>(desiredSet);
        toAdd.removeAll(existingSet);

        if (!toRemove.isEmpty()) {
            removeRealmRoles(token, userId, List.copyOf(toRemove));
        }
        if (!toAdd.isEmpty()) {
            assignRealmRoles(token, userId, List.copyOf(toAdd));
        }
    }



    public String createUserWithRoles(
            CreateUserRequest request,
            List<String> roles
    ) {

        String token = getAdminAccessToken();
        String userId = null;

        try {

            userId = createUser(token, request);

            if (roles != null && !roles.isEmpty()) {
                assignRealmRoles(token, userId, roles);
            }

            return userId;

        } catch (Exception ex) {

            if (userId != null) {
                deleteUser(token, userId);
            }

            throw new SystemException(
                    ErrorCode.KEYCLOAK_PROVISION_FAILED
            );
        }
    }
}
