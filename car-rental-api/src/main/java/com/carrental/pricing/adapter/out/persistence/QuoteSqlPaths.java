package com.carrental.pricing.adapter.out.persistence;

/** Đường dẫn SQL cố định của pricing; không nhận tên file từ request. */
final class QuoteSqlPaths {
    static final String INSERT = "sql/pricing/insert_quote.sql";
    static final String INSERT_LINE = "sql/pricing/insert_quote_line.sql";
    static final String FIND = "sql/pricing/find_quote_by_code.sql";
    static final String FIND_LINES = "sql/pricing/find_quote_lines.sql";
    /** Tiện ích hằng số không có instance. */
    private QuoteSqlPaths() { }
}
