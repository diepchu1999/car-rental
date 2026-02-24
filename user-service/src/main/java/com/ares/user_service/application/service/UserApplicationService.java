package com.ares.user_service.application.service;

import com.ares.user_service.api.request.ChangePasswordRequest;
import com.ares.user_service.api.request.CreateUserRequest;
import com.ares.user_service.api.request.UpdateUserRequest;
import com.ares.user_service.api.response.UserResponse;
import com.ares.user_service.application.mapper.UserApiMapper;
import com.ares.user_service.common.exception.ErrorCode;
import com.ares.user_service.common.exception.ResourceConflictException;
import com.ares.user_service.common.exception.ResourceNotFoundException;
import com.ares.user_service.common.exception.SystemException;
import com.ares.user_service.domain.model.User;
import com.ares.user_service.domain.model.UserStatus;
import com.ares.user_service.domain.repository.UserRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Transactional
public class UserApplicationService {

    private final UserRepository userRepository;
    private final UserApiMapper userMapper;
    private final KeyCloakAdminService keycloakAdminService;


    public Page<UserResponse> getAllUsers(
            int page,
            int size,
            String sort,
            String status,
            String role
    ) {
        return userRepository
                .findUsers(page, size, sort, status, role)
                .map(userMapper::toResponse);
    }

    public ResponseEntity<UserResponse> getUserById(String userId){
        User user = userRepository.getUserById(userId).orElseThrow(()
                -> new ResourceNotFoundException(ErrorCode.USER_NOT_FOUND));
        return ResponseEntity.ok(userMapper.toResponse(user));
    }

    public UserResponse createUser(CreateUserRequest request) {

        if (userRepository.existsByEmail(request.email())) {
            throw new ResourceConflictException(ErrorCode.USER_ALREADY_EXISTS);
        }

        //1. Save user
        User user = userMapper.toDomain(request);
        user.setStatus(UserStatus.INACTIVE);
        User saved = userRepository.save(user);
        //2.Save keyCloakUser
        try {
            String keycloakId =
                    keycloakAdminService.createUserWithRoles(
                            request,
                            request.roles()
                                    .stream()
                                    .map(Enum::name)
                                    .toList()
                    );
            saved.setKeyCloakId(keycloakId);
            saved.setStatus(UserStatus.ACTIVE);
            saved = userRepository.save(saved);
        } catch (Exception e) {
            throw new SystemException(ErrorCode.KEYCLOAK_PROVISION_FAILED);
        }

        return userMapper.toResponse(saved);
    }

    public ResponseEntity<UserResponse> updateUserById(String userId, UpdateUserRequest request) {
        User user = userRepository.getUserById(userId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.USER_NOT_FOUND));

        if (request.email() != null
                && (user.getEmail() == null || !request.email().equalsIgnoreCase(user.getEmail()))
                && userRepository.existsByEmail(request.email())) {
            throw new ResourceConflictException(ErrorCode.USER_ALREADY_EXISTS);
        }

        if (request.username() != null) {
            user.setUsername(request.username());
        }
        if (request.email() != null) {
            user.setEmail(request.email());
        }
        if (request.firstName() != null) {
            user.setFirstName(request.firstName());
        }
        if (request.lastName() != null) {
            user.setLastName(request.lastName());
        }
        if (request.status() != null) {
            user.setStatus(request.status());
        }
        if (request.roles() != null) {
            user.setRoles(request.roles());
        }

        User saved = userRepository.save(user);

        String keycloakId = saved.getKeyCloakId();
        if (keycloakId != null && !keycloakId.isBlank()) {
            try {
                String token = keycloakAdminService.getAdminAccessToken();
                keycloakAdminService.updateUser(token, keycloakId, saved);
                if (request.roles() != null) {
                    keycloakAdminService.syncRealmRoles(
                            token,
                            keycloakId,
                            request.roles().stream().map(Enum::name).toList()
                    );
                }
            } catch (Exception ex) {
                throw new SystemException(
                        ErrorCode.KEYCLOAK_PROVISION_FAILED,
                        "Failed to update user in Keycloak"
                );
            }
        }

        return ResponseEntity.ok(userMapper.toResponse(saved));
    }

    public ResponseEntity<Void> changePassword(String userId, ChangePasswordRequest request) {
        User user = userRepository.getUserById(userId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.USER_NOT_FOUND));

        if (request.newPassword() == null || request.newPassword().isBlank()) {
            throw new SystemException(
                    ErrorCode.INVALID_REQUEST,
                    "Password must not be blank"
            );
        }

        String keycloakId = user.getKeyCloakId();
        if (keycloakId == null || keycloakId.isBlank()) {
            throw new SystemException(
                    ErrorCode.KEYCLOAK_PROVISION_FAILED,
                    "Missing Keycloak user id"
            );
        }

        boolean temporary = Boolean.TRUE.equals(request.temporary());
        try {
            String token = keycloakAdminService.getAdminAccessToken();
            keycloakAdminService.resetPassword(token, keycloakId, request.newPassword(), temporary);
        } catch (Exception ex) {
            throw new SystemException(
                    ErrorCode.KEYCLOAK_PROVISION_FAILED,
                    "Failed to change password in Keycloak"
            );
        }

        return ResponseEntity.noContent().build();
    }

    public ResponseEntity<Void> deleteUserById(String userId) {
        User user = userRepository.getUserById(userId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.USER_NOT_FOUND));

        String keycloakId = user.getKeyCloakId();
        if (keycloakId != null && !keycloakId.isBlank()) {
            try {
                String token = keycloakAdminService.getAdminAccessToken();
                keycloakAdminService.deleteUser(token, keycloakId);
            } catch (Exception ex) {
                throw new SystemException(
                        ErrorCode.KEYCLOAK_PROVISION_FAILED,
                        "Failed to delete user in Keycloak"
                );
            }
        }

        userRepository.deleteUserById(userId);
        return ResponseEntity.noContent().build();
    }
}
