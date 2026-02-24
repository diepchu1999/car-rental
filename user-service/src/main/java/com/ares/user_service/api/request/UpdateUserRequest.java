package com.ares.user_service.api.request;

import com.ares.user_service.domain.model.UserRole;
import com.ares.user_service.domain.model.UserStatus;

import java.util.List;

public record UpdateUserRequest(
        String username,
        String email,
        String firstName,
        String lastName,
        UserStatus status,
        List<UserRole> roles
) {}
