package com.carrental.pricing.adapter.out.persistence;

import com.carrental.pricing.application.port.out.ReadQuotePort;
import com.carrental.pricing.application.view.QuoteDetail;
import com.carrental.shared.sql.SqlLoader;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Types;
import java.util.Optional;

/** Đọc header và dòng đã lưu bằng SQL riêng của pricing, không tính lại báo giá cũ. */
@Repository
class QuoteReadAdapter implements ReadQuotePort {
    private final NamedParameterJdbcTemplate jdbc;
    private final String find;
    private final String findLines;

    /** Tải hai câu SQL một lần, không đọc file trong từng request. */
    QuoteReadAdapter(NamedParameterJdbcTemplate jdbc, SqlLoader loader) {
        this.jdbc = jdbc; find = loader.load(QuoteSqlPaths.FIND); findLines = loader.load(QuoteSqlPaths.FIND_LINES);
    }

    /** Không lọc hạn và không biến lỗi mapping/toàn vẹn thành trường hợp không tìm thấy. */
    @Override
    public Optional<QuoteDetail> findByCode(String code) {
        var parameters = new MapSqlParameterSource().addValue("code", code, Types.VARCHAR);
        return DataAccessUtils.optionalResult(jdbc.query(find, parameters, QuoteRowMappers.HEADER))
                .map(header -> header.detail(jdbc.query(findLines,
                        new MapSqlParameterSource().addValue("quoteId", header.id(), Types.BIGINT), QuoteRowMappers.LINE)));
    }
}
