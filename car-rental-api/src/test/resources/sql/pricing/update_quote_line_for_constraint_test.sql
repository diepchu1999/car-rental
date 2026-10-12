-- Không sửa nội dung dòng đã chốt, kể cả gán lại cùng giá trị.
UPDATE pricing.quote_line SET amount = amount
WHERE quote_id = (SELECT id FROM pricing.quote WHERE code = :code);
