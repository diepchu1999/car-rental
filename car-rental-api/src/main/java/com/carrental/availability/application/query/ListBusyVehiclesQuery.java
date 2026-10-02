package com.carrental.availability.application.query;

import com.carrental.availability.domain.ReservationPeriod;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.validation.Validations;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Set;

/**
 * Đầu vào tra cứu xe bận theo BR-104, BR-109 và BR-116.
 *
 * <p>Khoảng thuê chưa cộng đệm; không nhận loại gói thuê hay loại sở hữu.
 * Chụp lại tập ứng viên để bên gọi không thể sửa đầu vào sau khi kiểm tra.
 *
 * @param rentalPeriod khoảng thuê hữu hạn chưa cộng đệm
 * @param buffer đệm không âm do bên gọi phân giải
 * @param candidateIds các ID xe dương, được sao chép và loại trùng
 */
public record ListBusyVehiclesQuery(
        ReservationPeriod rentalPeriod,
        Duration buffer,
        Collection<Long> candidateIds
) {

    /** Kiểm tra đầu vào kể cả khi gọi trực tiếp constructor thay vì factory. */
    public ListBusyVehiclesQuery {
        rentalPeriod = Validations.required(rentalPeriod, "rentalPeriod");
        if (rentalPeriod.isUnbounded()) {
            throw DomainException.invalidInput("A finite rentalPeriod is required.");
        }
        buffer = Validations.required(buffer, "buffer");
        if (buffer.isNegative()) {
            throw DomainException.invalidInput("buffer must not be negative.");
        }
        candidateIds = Validations.required(candidateIds, "candidateIds");
        for (Long candidateId : candidateIds) {
            if (candidateId == null || candidateId <= 0L) {
                throw DomainException.invalidInput("Every candidateId must be greater than zero.");
            }
        }
        candidateIds = Set.copyOf(candidateIds);
    }

    /**
     * Tạo query từ giá trị thô, không phụ thuộc DTO của api hoặc adapter.
     *
     * @param startInclusive thời điểm bắt đầu thuê
     * @param endExclusive thời điểm kết thúc thuê, bắt buộc
     * @param buffer đệm chưa cộng vào khoảng thuê
     * @param candidateIds tập xe cần kiểm tra, có thể rỗng nhưng không null
     * @return query đã kiểm tra
     * @throws DomainException nếu khoảng thuê, đệm hoặc tập xe không hợp lệ
     */
    public static ListBusyVehiclesQuery from(
            Instant startInclusive, Instant endExclusive,
            Duration buffer, Collection<Long> candidateIds
    ) {
        return new ListBusyVehiclesQuery(
                ReservationPeriod.finite(startInclusive, endExclusive), buffer, candidateIds
        );
    }
}
