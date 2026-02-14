package com.ares.user_service.api.request;

public record ChangePasswordRequest(
        String newPassword,
        Boolean temporary
) {}
