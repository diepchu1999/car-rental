package com.ares.user_service.application.service;

import com.ares.user_service.api.response.UserResponse;
import com.ares.user_service.application.mapper.UserMapper;
import com.ares.user_service.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserApplicationService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;

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
}
