package com.ares.user_service.infrastructure.persistence.adapter;

import com.ares.user_service.domain.model.User;
import com.ares.user_service.domain.model.UserRole;
import com.ares.user_service.domain.model.UserStatus;
import com.ares.user_service.domain.repository.UserRepository;
import com.ares.user_service.infrastructure.persistence.entity.UserEntity;
import com.ares.user_service.infrastructure.persistence.entity.UserRoleEntity;
import com.ares.user_service.infrastructure.persistence.jpa.UserJpaRepository;
import com.ares.user_service.infrastructure.persistence.mapper.UserPersistenceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
@Slf4j
public class UserRepositoryAdapter implements UserRepository {
    private final UserJpaRepository jpaRepository;
    private final UserPersistenceMapper mapper;

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
        Page<UserEntity> users = jpaRepository.findWithFilter(status, role, pageable);
        log.debug("users: {}", new ArrayList<>(users.getContent()));
        return users.map(mapper::toDomain);
    }


    @Override
    public boolean existsByEmail(String email) {
        return jpaRepository.existsByEmail(email);
    }

    @Override
    public User save(User user) {
        UserEntity entity = mapper.toEntity(user);
        return mapper.toDomain(jpaRepository.save(entity));
    }


    }
