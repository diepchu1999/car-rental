package com.carrental.identity.adapter.out.persistence;

import com.carrental.identity.domain.Account;
import com.carrental.identity.domain.VietnamMobileNumber;
import com.carrental.shared.sql.SqlLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.IncorrectUpdateSemanticsDataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Kiểm lỗi JDBC không bị biến thành kết quả trùng mã; ràng buộc và cuộc đua dùng integration test. */
class AccountWriteAdapterTest {
    private NamedParameterJdbcTemplate jdbc;
    private AccountWriteAdapter adapter;
    private String insertSql;

    /** Dùng SQL thật với JDBC giả để chủ động tạo lỗi hạ tầng hiếm gặp. */
    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        var loader = new SqlLoader();
        insertSql = loader.load("sql/identity/insert_account.sql");
        adapter = new AccountWriteAdapter(jdbc, loader);
    }

    /** Lỗi kết nối đi ra nguyên object, không có lần đọc phân loại hoặc retry ngầm. */
    @Test
    void propagatesConnectionFailureWithoutRetry() {
        var failure = new DataAccessResourceFailureException("Simulated connection failure.");
        when(jdbc.update(eq(insertSql), any(SqlParameterSource.class))).thenThrow(failure);
        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> adapter.insert(account(), Instant.EPOCH)));
        verify(jdbc).update(eq(insertSql), any(SqlParameterSource.class));
        verifyNoMoreInteractions(jdbc);
    }

    /** CHECK/NOT NULL hoặc lỗi toàn vẹn khác không được bắt rộng rồi đổi thành CODE_COLLISION. */
    @Test
    void propagatesIntegrityFailureWithoutReclassification() {
        var failure = new DataIntegrityViolationException("Simulated integrity failure.");
        when(jdbc.update(eq(insertSql), any(SqlParameterSource.class))).thenThrow(failure);
        assertSame(failure, assertThrows(DataIntegrityViolationException.class,
                () -> adapter.insert(account(), Instant.EPOCH)));
        verify(jdbc).update(eq(insertSql), any(SqlParameterSource.class));
        verifyNoMoreInteractions(jdbc);
    }

    /** Số dòng khác 0/1 phải báo lỗi chứ không được nhận là chèn thành công hoặc trùng mã. */
    @ParameterizedTest
    @ValueSource(ints = {-1, 2})
    void rejectsUnexpectedAffectedRowCounts(int rows) {
        when(jdbc.update(eq(insertSql), any(SqlParameterSource.class))).thenReturn(rows);
        assertThrowsExactly(IncorrectUpdateSemanticsDataAccessException.class,
                () -> adapter.insert(account(), Instant.EPOCH));
        verify(jdbc).update(eq(insertSql), any(SqlParameterSource.class));
        verifyNoMoreInteractions(jdbc);
    }

    /** Tài khoản hoặc mốc tạo null bị chặn trước JDBC, không dùng now hoặc danh tính giả làm mặc định. */
    @Test
    void rejectsMissingWriteInputsBeforeJdbc() {
        assertThrowsExactly(NullPointerException.class, () -> adapter.insert(null, Instant.EPOCH));
        assertThrowsExactly(NullPointerException.class, () -> adapter.insert(account(), null));
        assertThrowsExactly(NullPointerException.class, () -> adapter.addConsentIfAbsent(null));
        verifyNoInteractions(jdbc);
    }

    /** Dữ liệu giả tối thiểu không cần mock domain hoặc tạo người dùng Keycloak. */
    private static Account account() {
        return Account.restore("TK-ABC123", "test-subject", new VietnamMobileNumber("0912345678"), null);
    }
}
