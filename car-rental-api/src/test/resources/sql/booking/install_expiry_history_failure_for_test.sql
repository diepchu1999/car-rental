-- Chỉ trong PostgreSQL Testcontainers: một đơn lỗi, những đơn khác vẫn ghi được.
CREATE FUNCTION booking.reject_one_expiry_for_test() RETURNS TRIGGER LANGUAGE plpgsql AS $body$
BEGIN
    IF NEW.reason = 'HOLD_EXPIRED' AND EXISTS (
        SELECT 1 FROM booking.booking WHERE id = NEW.booking_id AND code = 'DT-2611-EXFAIL'
    ) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'Simulated expiry history failure.',
            CONSTRAINT = 'test_expiry_history_failure';
    END IF;
    RETURN NEW;
END;
$body$;
CREATE TRIGGER test_reject_one_expiry BEFORE INSERT ON booking.status_history
FOR EACH ROW EXECUTE FUNCTION booking.reject_one_expiry_for_test();
