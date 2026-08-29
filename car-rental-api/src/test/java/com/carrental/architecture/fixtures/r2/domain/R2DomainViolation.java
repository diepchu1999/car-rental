package com.carrental.architecture.fixtures.r2.domain;

import com.carrental.architecture.fixtures.r2.adapter.R2AdapterType;
import com.carrental.architecture.fixtures.r2.application.R2ApplicationType;
import org.springframework.jdbc.core.JdbcTemplate;

public final class R2DomainViolation {

    public void acceptForbiddenDependencies(
            R2ApplicationType applicationType,
            R2AdapterType adapterType,
            JdbcTemplate jdbcTemplate
    ) {
    }
}
