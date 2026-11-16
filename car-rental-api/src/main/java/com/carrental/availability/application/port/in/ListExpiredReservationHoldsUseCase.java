package com.carrental.availability.application.port.in;

import com.carrental.availability.api.ExpiredHoldRef;
import com.carrental.availability.application.query.ListExpiredReservationHoldsQuery;
import java.util.List;

/** Danh sách ứng viên cho job BR-103; đọc không nhả giữ chỗ. */
public interface ListExpiredReservationHoldsUseCase {
    /** Chỉ HELD tới hạn, sắp ổn định theo hạn rồi mã; kết quả có thể cũ khi xử lý từng dòng. */
    List<ExpiredHoldRef> list(ListExpiredReservationHoldsQuery query);
}
