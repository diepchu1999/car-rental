package com.ares.user_service.domain.repository;

import com.ares.user_service.domain.model.User;
import org.springframework.data.domain.Page;

import java.util.Optional;

public interface UserRepository {
    Page<User> findUsers(
            int page,
            int size,
            String sort,
            String status,
            String role
    );

    Optional<User> getUserById(String userId);

    boolean existsByEmail(String email);

    User save (User user);

    void deleteUserById(String userId);
}
