package com.ares.user_service.domain.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;


@Getter
@AllArgsConstructor
public class User {
    private UUID id;
    private String email;
    private String fullName;
    private UserStatus status;
    private List<UserRole> roles;
    private Instant createdAt;
}
