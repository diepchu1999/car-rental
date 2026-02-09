package com.ares.user_service.infrastructure.persistence.jpa;

import com.ares.user_service.infrastructure.persistence.entity.UserEntity;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.util.UUID;


public interface UserJpaRepository extends JpaRepository<UserEntity, UUID> {


    @Query("""
        SELECT DISTINCT u FROM UserEntity u
        LEFT JOIN u.roles r
        WHERE (:status IS NULL OR u.status = :status)
          AND (:role IS NULL OR r.role = :role)
    """)
    Page<UserEntity> findWithFilter(
            @Param("status") String status,
            @Param("role") String role,
            Pageable pageable
    );
}
