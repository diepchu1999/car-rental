-- Chỉ dùng trong transaction của test, lỗi INSERT làm rollback cả trigger/function này.
CREATE FUNCTION booking.fail_history_for_adapter_test() RETURNS TRIGGER LANGUAGE plpgsql AS $test$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'Simulated history persistence failure.',
        CONSTRAINT = 'test_booking_history_failure';
END;
$test$;
CREATE TRIGGER trg_booking_history_failure_for_test BEFORE INSERT ON booking.status_history
    FOR EACH ROW EXECUTE FUNCTION booking.fail_history_for_adapter_test();
