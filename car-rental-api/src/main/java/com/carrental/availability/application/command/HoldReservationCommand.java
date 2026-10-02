package com.carrental.availability.application.command;

import com.carrental.availability.domain.ReservationPeriod;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.validation.Validations;

import java.time.Duration;
import java.time.Instant;

/**
 * Đầu vào đã kiểm tra của luồng giữ chỗ theo BR-102, BR-103, BR-104.
 *
 * <p>Không nhận TTL hoặc mã reservation từ bên gọi. Khoảng thuê chưa cộng
 * đệm; service áp dụng buffer theo BR-109, BR-116 trước khi ghi.
 * Không biết loại gói thuê, loại sở hữu hoặc giờ làm việc chi nhánh.
 *
 * @param vehicleId định danh xe lớn hơn không
 * @param rentalPeriod khoảng thuê hữu hạn chưa cộng đệm
 * @param buffer khoảng đệm không âm do bên gọi phân giải
 * @param bookingCode mã đơn có nội dung, không phải mã reservation
 */
public record HoldReservationCommand(
        long vehicleId,
        ReservationPeriod rentalPeriod,
        Duration buffer,
        String bookingCode
) {

    /**
     * Bảo vệ đầu vào kể cả khi không đi qua factory.
     *
     * @throws DomainException nếu xe, khoảng thuê, đệm hoặc mã đơn không hợp lệ
     */
    public HoldReservationCommand {
        if (vehicleId <= 0L) {
            throw DomainException.invalidInput("vehicleId must be greater than zero.");
        }
        rentalPeriod = Validations.required(rentalPeriod, "rentalPeriod");
        if (rentalPeriod.isUnbounded()) {
            throw DomainException.invalidInput("A finite rentalPeriod is required.");
        }
        buffer = Validations.required(buffer, "buffer");
        if (buffer.isNegative()) {
            throw DomainException.invalidInput("buffer must not be negative.");
        }
        bookingCode = Validations.requiredText(bookingCode, "bookingCode");
    }

    /**
     * Tạo command từ giá trị thô, không phụ thuộc DTO của adapter hoặc api.
     *
     * @param vehicleId định danh xe, có thể null nếu đầu vào thiếu
     * @param startInclusive thời điểm bắt đầu thuê
     * @param endExclusive thời điểm kết thúc thuê, bắt buộc có
     * @param buffer khoảng đệm chưa được cộng vào khoảng thuê
     * @param bookingCode mã đơn thuê
     * @return command đã kiểm tra
     * @throws DomainException nếu dữ liệu thiếu hoặc không hợp lệ
     */
    public static HoldReservationCommand from(
            Long vehicleId, Instant startInclusive, Instant endExclusive,
            Duration buffer, String bookingCode
    ) {
        return new HoldReservationCommand(
                Validations.required(vehicleId, "vehicleId"),
                ReservationPeriod.finite(startInclusive, endExclusive),
                buffer, bookingCode
        );
    }
}
