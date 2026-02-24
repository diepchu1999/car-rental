package com.ares.user_service.application.mapper;


import com.ares.user_service.api.request.CreateUserRequest;
import com.ares.user_service.api.response.UserResponse;
import com.ares.user_service.domain.model.User;
import com.ares.user_service.domain.model.UserStatus;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class UserApiMapper  {

    /**
     * Request -> Domain
     */
    public User toDomain(CreateUserRequest request) {

        return User.builder()
                .id(null)
                .username(request.username())
                .email(request.email())
                .firstName(request.firstName())
                .lastName(request.lastName())
                .roles(request.roles())
                .status(UserStatus.ACTIVE)
                .createdAt(Instant.now())
                .build();
    }

    /**
     * Domain -> Response
     */
    public UserResponse toResponse(User user) {

        return new UserResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                user.getStatus(),
                user.getRoles(),
                user.getCreatedAt()
        );
    }



}
