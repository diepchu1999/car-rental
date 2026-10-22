-- Chờ cả hai request thật đã tới PostgreSQL trước khi nhả hàng challenge.
SELECT count(*) FROM pg_stat_activity
WHERE datname = current_database() AND wait_event_type = 'Lock'
  AND query LIKE '%car_rental_otp%';
