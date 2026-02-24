package com.ares.user_service.infrastructure.persistence.jpa;

import com.ares.user_service.infrastructure.persistence.entity.UserEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;


public interface UserJpaRepository extends JpaRepository<UserEntity, UUID> {

    @EntityGraph(attributePaths = "roles") //Prevent N+1 when use toDomain() method
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

    boolean existsByEmail(String email);
}
