package com.ares.user_service.infrastructure.persistence.mapper;

import com.ares.user_service.domain.model.User;
import com.ares.user_service.domain.model.UserRole;
import com.ares.user_service.domain.model.UserStatus;
import com.ares.user_service.infrastructure.persistence.entity.UserEntity;
import com.ares.user_service.infrastructure.persistence.entity.UserRoleEntity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class UserPersistenceMapper {
    /*
     * DOMAIN -> ENTITY
     */
    public UserEntity toEntity(User user) {

        if (user == null) {
            return null;
        }

        UserEntity entity = UserEntity.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .status(user.getStatus() != null ? user.getStatus().name() : null)
                .createdAt(user.getCreatedAt())
                .build();

        if (user.getRoles() != null) {

            List<UserRoleEntity> roleEntities = new ArrayList<>();

            for (UserRole role : user.getRoles()) {

                UserRoleEntity roleEntity = new UserRoleEntity();
                roleEntity.setRole(role);
                roleEntity.setUser(entity);
                roleEntities.add(roleEntity);
            }

            entity.setRoles(roleEntities);
        }

        return entity;
    }

    /*
     * ENTITY -> DOMAIN
     */
    public User toDomain(UserEntity entity) {

        if (entity == null) {
            return null;
        }

        List<UserRole> roles = entity.getRoles() == null
                ? List.of()
                : entity.getRoles()
                .stream()
                .map(UserRoleEntity::getRole)
                .toList();

        return User.builder()
                .id(entity.getId())
                .username(entity.getUsername())
                .email(entity.getEmail())
                .firstName(entity.getFirstName())
                .lastName(entity.getLastName())
                .status(
                        entity.getStatus() != null
                                ? UserStatus.valueOf(entity.getStatus())
                                : null
                )
                .createdAt(entity.getCreatedAt())
                .roles(roles)
                .build();
    }
}
