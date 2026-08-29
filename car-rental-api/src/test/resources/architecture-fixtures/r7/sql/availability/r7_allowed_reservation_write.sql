UPDATE availability.reservation
SET status = 'RELEASED'
WHERE id = :reservation_id;
