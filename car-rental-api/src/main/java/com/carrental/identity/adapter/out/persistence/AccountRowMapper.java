package com.carrental.identity.adapter.out.persistence;

import com.carrental.identity.domain.Account;
import com.carrental.identity.domain.TermsConsent;
import com.carrental.identity.domain.VietnamMobileNumber;
import com.carrental.shared.error.DomainException;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;

/** Khôi phục aggregate, không tự chữa dữ liệu CSDL thiếu bằng chứng hoặc số chưa chuẩn hoá. */
final class AccountRowMapper implements RowMapper<Account> {
    /** Tạo mapper không giữ trạng thái dùng chung giữa các lần đọc. */
    AccountRowMapper() { }

    /** Dữ liệu lưu sai là lỗi nội bộ, không biến thành lỗi đầu vào 400/403 của khách. */
    @Override
    public Account mapRow(ResultSet row, int rowNum) throws SQLException {
        try {
            String phone = row.getString("phone_number");
            VietnamMobileNumber number = new VietnamMobileNumber(phone);
            if (!number.value().equals(phone)) {
                throw new IllegalStateException("Stored account phone is not canonical.");
            }
            String version = row.getString("terms_version");
            OffsetDateTime at = row.getObject("consented_at", OffsetDateTime.class);
            TermsConsent consent = version == null && at == null ? null
                    : new TermsConsent(version, at == null ? null : at.toInstant());
            return Account.restore(row.getString("code"), row.getString("keycloak_subject"), number, consent);
        } catch (DomainException invalidStoredData) {
            throw new IllegalStateException("Stored account data violates the identity contract.", invalidStoredData);
        }
    }
}
