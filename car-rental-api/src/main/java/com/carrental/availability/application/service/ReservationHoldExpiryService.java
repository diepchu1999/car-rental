package com.carrental.availability.application.service;

import com.carrental.availability.api.ExpiredHoldRef;
import com.carrental.availability.api.HoldExpiryResult;
import com.carrental.availability.application.command.ExpireReservationHoldCommand;
import com.carrental.availability.application.query.ListExpiredReservationHoldsQuery;
import com.carrental.availability.application.port.in.ExpireReservationHoldUseCase;
import com.carrental.availability.application.port.in.ListExpiredReservationHoldsUseCase;
import com.carrental.availability.application.port.out.ReadReservationPort;
import com.carrental.availability.application.port.out.WriteReservationPort;
import com.carrental.availability.domain.ReservationKind;
import com.carrental.availability.domain.ReservationStatus;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.shared.validation.Validations;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** BR-103: kiểm điều kiện trong câu ghi, không tin danh sách ứng viên hoặc tính lại TTL. */
@Service
class ReservationHoldExpiryService implements ExpireReservationHoldUseCase, ListExpiredReservationHoldsUseCase {
    private final ReadReservationPort reads;
    private final WriteReservationPort writes;

    /** Không nhận Clock/policy; bên điều phối cấp một mốc thống nhất cho cả lượt. */
    ReservationHoldExpiryService(ReadReservationPort reads, WriteReservationPort writes) {
        this.reads = reads;
        this.writes = writes;
    }

    /** Chỉ đọc ứng viên, không mở transaction dài bao quanh cả lượt xử lý. */
    @Override
    @Transactional(readOnly = true)
    public List<ExpiredHoldRef> list(ListExpiredReservationHoldsQuery query) {
        return List.copyOf(reads.findExpiredHolds(Validations.required(query, "query").expiredAt()));
    }

    /**
     * Tham gia transaction ngoài; adapter dùng savepoint/retry cho câu ghi (backend-guideline §3).
     * Khi không đổi dòng nào, đọc lại để phân biệt đã RELEASED với chưa được phép nhả.
     * RELEASED là trạng thái cuối nên kết quả đã nhả không thể quay lại chặn lịch.
     */
    @Override
    @Transactional
    public HoldExpiryResult expire(ExpireReservationHoldCommand command) {
        var checked = Validations.required(command, "command");
        if (writes.releaseExpiredHold(checked.code(), checked.expiredAt())) return HoldExpiryResult.RELEASED;
        var saved = reads.loadAggregate(checked.code())
                .orElseThrow(() -> DomainException.notFound(ErrorCode.RESERVATION_NOT_FOUND));
        if (saved.kind() == ReservationKind.RENTAL && saved.status() == ReservationStatus.RELEASED
                && saved.holdExpiresAt() != null && !saved.holdExpiresAt().isAfter(checked.expiredAt())) {
            return HoldExpiryResult.ALREADY_RELEASED;
        }
        return HoldExpiryResult.NOT_ELIGIBLE;
    }
}
