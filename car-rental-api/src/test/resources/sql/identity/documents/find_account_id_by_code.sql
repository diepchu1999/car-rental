-- Chỉ dựng fixture cho kiểm HTTP trong container, không dùng ID từ client.
SELECT id FROM identity.account WHERE code = :code;
