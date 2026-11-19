-- Chỉ dọn trigger thử trong container test, không chạm ràng buộc nghiệp vụ.
DROP TRIGGER test_reject_one_expiry ON booking.status_history;
DROP FUNCTION booking.reject_one_expiry_for_test();
