package com.ares.user_service.api.request;


import com.ares.user_service.domain.model.UserRole;

import java.util.List;

public record CreateUserRequest(
        String username,
        String email,
        String firstName,
        String lastName,
        String password,
        List<UserRole> roles
) {}
