package com.ares.user_service.application.mapper;


import com.ares.user_service.api.response.UserResponse;
import com.ares.user_service.domain.model.User;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

@Component
public class UserMapper {
    public UserResponse toResponse(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .status(user.getStatus().name())
                .roles(
                        user.getRoles().stream()
                                .map(Enum::name)
                                .collect(Collectors.toList())
                )
                .createdAt(user.getCreatedAt())
                .build();
    }
}
