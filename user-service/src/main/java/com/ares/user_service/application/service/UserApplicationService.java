package com.ares.user_service.application.service;

import com.ares.user_service.api.request.CreateUserRequest;
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
}
