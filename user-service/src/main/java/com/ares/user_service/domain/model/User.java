package com.ares.user_service.domain.model;

import lombok.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Getter
@AllArgsConstructor
@Builder
@Setter
@NoArgsConstructor
public class User {
    private UUID id;
    private String keyCloakId;
    private String username;
    private String email;
    private String firstName;
    private String lastName;
    private UserStatus status;
    private Instant createdAt;
    private List<UserRole> roles;
}