-- Control: write keywords inside comments are not executable SQL.
-- UPDATE availability.reservation SET status = 'RELEASED';

/*
INSERT INTO availability.reservation (vehicle_id) VALUES (999);
*/

SELECT reservation.id
FROM availability.reservation AS reservation
WHERE reservation.id = :reservation_id;

SELECT 'DELETE FROM availability.reservation' AS documentation_only;
SELECT $$TRUNCATE availability.reservation$$ AS another_example;

UPDATE availability.reservation
SET status = 'RELEASED'
WHERE id = :reservation_id;
