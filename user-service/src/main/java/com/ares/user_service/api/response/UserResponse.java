package com.ares.user_service.api.response;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
@Getter
@Builder
public class UserResponse {
    private UUID id;
    private String email;
    private String fullName;
    private String status;
    private List<String> roles;
    private Instant createdAt;
}
