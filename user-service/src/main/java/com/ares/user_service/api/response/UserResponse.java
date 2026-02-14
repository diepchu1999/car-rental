package com.ares.user_service.api.response;
import com.ares.user_service.domain.model.UserRole;
import com.ares.user_service.domain.model.UserStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String username,
        String email,
        String firstName,
        String lastName,
        UserStatus status,
        List<UserRole> roles,
        Instant createdAt
) {}