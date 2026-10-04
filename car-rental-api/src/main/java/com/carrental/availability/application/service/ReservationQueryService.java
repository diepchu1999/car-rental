package com.carrental.availability.application.service;

import com.carrental.availability.application.port.in.ListBusyVehiclesUseCase;
import com.carrental.availability.application.port.out.ReadReservationPort;
import com.carrental.availability.application.query.ListBusyVehiclesQuery;
import com.carrental.availability.domain.ReservationPeriod;
import com.carrental.shared.validation.Validations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * Điều phối tra cứu xe bận theo BR-104 và áp dụng đệm theo BR-109, BR-116.
 *
 * <p>Đây chỉ là luồng đọc. Không giữ chỗ, không đổi trạng thái, không kiểm
 * xe trống trước khi ghi. Luồng hold vẫn để PostgreSQL quyết định độc lập.
 */
@Service
class ReservationQueryService implements ListBusyVehiclesUseCase {

    private final ReadReservationPort reads;

    /** Nhận cổng đọc dùng chung của tài nguyên reservation. */
    ReservationQueryService(ReadReservationPort reads) {
        this.reads = reads;
    }

    /**
     * Cộng đệm vào cuối khoảng bằng cùng phép tính với hold rồi đọc xe bận.
     *
     * <p>Vẫn kiểm tràn thời gian khi tập ứng viên rỗng; không gọi persistence
     * cho tập rỗng. Lỗi lưu trữ được truyền nguyên trạng, không báo xe rảnh.
     *
     * @param query yêu cầu tra cứu, không null
     * @return tập bất biến chứa các ID xe bận
     */
    @Override
    @Transactional(readOnly = true)
    public Set<Long> listBusyVehicleIds(ListBusyVehiclesQuery query) {
        Validations.required(query, "query");
        ReservationPeriod bufferedPeriod = query.rentalPeriod().withBuffer(query.buffer());
        if (query.candidateIds().isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(reads.findBusyVehicleIds(bufferedPeriod, query.candidateIds()));
    }
}
