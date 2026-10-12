-- Kiểm rollback ở context test riêng, không dùng CSDL local.
SELECT count(*) FROM pricing.quote_line;
