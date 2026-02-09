package com.ares.user_service.infrastructure.persistence.adapter;

import com.ares.user_service.domain.model.User;
import com.ares.user_service.domain.model.UserRole;
import com.ares.user_service.domain.model.UserStatus;
import com.ares.user_service.domain.repository.UserRepository;
import com.ares.user_service.infrastructure.persistence.entity.UserEntity;
import com.ares.user_service.infrastructure.persistence.jpa.UserJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class UserRepositoryAdapter implements UserRepository {
    private final UserJpaRepository jpaRepository;

    @Override
    public Page<User> findUsers(int page, int size, String sort, String status, String role) {

        String[] sortParts = sort.split(",");
        Sort.Direction direction =
                sortParts.length > 1 && sortParts[1].equalsIgnoreCase("asc")
                        ? Sort.Direction.ASC
                        : Sort.Direction.DESC;

        Pageable pageable = PageRequest.of(
                page,
                size,
                Sort.by(direction, sortParts[0])
        );

        return jpaRepository.findWithFilter(status, role, pageable)
                .map(this::toDomain);
    }

    private User toDomain(UserEntity entity) {
        return new User(
                entity.getId(),
                entity.getEmail(),
                entity.getFullName(),
                UserStatus.valueOf(entity.getStatus()),
                entity.getRoles().stream()
                        .map(r -> UserRole.valueOf(r.getRole()))
                        .collect(Collectors.toList()),
                entity.getCreatedAt()
        );
    }
}
