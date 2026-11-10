-- Cố đổi giá đã đóng băng nhưng giữ tổng/cọc/phần còn lại nhất quán.
UPDATE booking.booking SET rental_amount = 2000000, total_amount = 2100000, deposit = 630000, balance = 1470000 WHERE id = :id;
