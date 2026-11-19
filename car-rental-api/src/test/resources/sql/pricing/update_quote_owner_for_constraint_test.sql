-- BR-122: thử đổi chủ bằng SQL tay phải bị trigger bất biến V009 từ chối.
UPDATE pricing.quote SET customer_account_code = 'TK-OTHER1' WHERE code = :code;
