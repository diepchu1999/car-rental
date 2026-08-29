package com.carrental.architecture.fixtures.r11.application.service;

import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;
import jakarta.servlet.ServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.Connection;
import javax.sql.DataSource;
import com.fasterxml.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.*;

import static org.springframework.http.HttpStatus.OK;

/**
 * Cố ý chứa các import bị R11 cấm trong application.
 *
 * <p>File là tài nguyên để phân tích cú pháp, không được biên dịch.
 * Không cần thêm dependency chỉ để làm các import này tồn tại.
 *
 * <p>Mỗi câu import phải tạo một vi phạm riêng.
 */
class R11ForbiddenApplicationImports {
}