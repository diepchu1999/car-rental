-- BR-103/304: nhả riêng một hold trong transaction của bên điều phối; không tính lại TTL.
UPDATE availability.reservation
SET status = 'RELEASED', status_changed_at = :expiredAt
WHERE code = :code AND status = 'HELD' AND hold_expires_at <= :expiredAt;
