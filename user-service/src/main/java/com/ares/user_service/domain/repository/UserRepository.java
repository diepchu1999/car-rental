package com.ares.user_service.domain.repository;

import com.ares.user_service.domain.model.User;
import org.springframework.data.domain.Page;

public interface UserRepository {
    Page<User> findUsers(
            int page,
            int size,
            String sort,
            String status,
            String role
    );
}
