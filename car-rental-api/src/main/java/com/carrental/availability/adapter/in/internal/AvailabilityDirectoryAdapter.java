package com.carrental.availability.adapter.in.internal;

import com.carrental.availability.api.AvailabilityDirectory;
import com.carrental.availability.api.BlockKind;
import com.carrental.availability.api.Period;
import com.carrental.availability.api.ReservationRef;
import com.carrental.availability.application.command.BlockReservationCommand;
import com.carrental.availability.application.command.CompleteReservationCommand;
import com.carrental.availability.application.command.ConfirmReservationCommand;
import com.carrental.availability.application.command.HoldReservationCommand;
import com.carrental.availability.application.command.MarkReservationInUseCommand;
import com.carrental.availability.application.command.MoveComplianceHoldStartCommand;
import com.carrental.availability.application.command.ReleaseReservationCommand;
import com.carrental.availability.application.port.in.BlockReservationUseCase;
import com.carrental.availability.application.port.in.CompleteReservationUseCase;
import com.carrental.availability.application.port.in.ConfirmReservationUseCase;
import com.carrental.availability.application.port.in.HoldReservationUseCase;
import com.carrental.availability.application.port.in.ListBusyVehiclesUseCase;
import com.carrental.availability.application.port.in.MarkReservationInUseUseCase;
import com.carrental.availability.application.port.in.MoveComplianceHoldStartUseCase;
import com.carrental.availability.application.port.in.ReleaseReservationUseCase;
import com.carrental.availability.application.query.ListBusyVehiclesQuery;
import com.carrental.availability.domain.ReservationKind;
import com.carrental.shared.validation.Validations;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Set;

/**
 * Nối hợp đồng cross-module với các use case nội bộ theo ADR-0004 và ADR-0008.
 *
 * <p>BR-102, BR-103, BR-104, BR-109, BR-116 và các cạnh status-flow §2 vẫn được
 * thực hiện trong application/domain. Adapter chỉ đổi kiểu dữ liệu, không cộng
 * đệm, đọc Clock, chọn TTL hoặc đọc kiểm xe trống trước khi giữ chỗ.
 *
 * <p>Gọi qua các bean use case đã được Spring bọc transaction; không tự tạo
 * transaction mới hay gọi thẳng implementation. Module ngoài chỉ import api.
 */
@Component
class AvailabilityDirectoryAdapter implements AvailabilityDirectory {

    private final HoldReservationUseCase holds;
    private final BlockReservationUseCase blocks;
    private final ConfirmReservationUseCase confirmations;
    private final ReleaseReservationUseCase releases;
    private final MarkReservationInUseUseCase departures;
    private final CompleteReservationUseCase completions;
    private final MoveComplianceHoldStartUseCase complianceMoves;
    private final ListBusyVehiclesUseCase queries;

    /** Nhận các cổng đầu vào; không phụ thuộc port ghi/đọc hoặc adapter persistence. */
    AvailabilityDirectoryAdapter(
            HoldReservationUseCase holds, BlockReservationUseCase blocks,
            ConfirmReservationUseCase confirmations, ReleaseReservationUseCase releases,
            MarkReservationInUseUseCase departures, CompleteReservationUseCase completions,
            ListBusyVehiclesUseCase queries, MoveComplianceHoldStartUseCase complianceMoves
    ) {
        this.holds = holds;
        this.blocks = blocks;
        this.confirmations = confirmations;
        this.releases = releases;
        this.departures = departures;
        this.completions = completions;
        this.queries = queries;
        this.complianceMoves = complianceMoves;
    }

    /** Chuyển khoảng thuê chưa cộng đệm vào command; use case tự áp dụng đệm và TTL. */
    @Override
    public ReservationRef hold(long vehicleId, Period rentalPeriod, Duration buffer, String bookingCode) {
        Period checked = Validations.required(rentalPeriod, "rentalPeriod");
        return holds.hold(HoldReservationCommand.from(vehicleId, checked.startInclusive(),
                checked.endExclusive(), buffer, bookingCode));
    }

    /** Giữ nguyên khoảng, cận trên null và lý do khi tạo khóa vận hành theo BR-104, BR-015. */
    @Override
    public ReservationRef block(long vehicleId, Period period, BlockKind kind, String reason) {
        Period checked = Validations.required(period, "period");
        return blocks.block(BlockReservationCommand.from(vehicleId, checked.startInclusive(),
                checked.endExclusive(), toReservationKind(Validations.required(kind, "kind")), reason));
    }

    /** Chuyển mã reservation nguyên văn sang use case xác nhận, không dùng bookingCode. */
    @Override
    public void confirm(String reservationCode) {
        confirmations.confirm(ConfirmReservationCommand.from(reservationCode));
    }

    /** Chuyển mã reservation sang use case nhả chỗ, không tự sửa trạng thái. */
    @Override
    public void release(String reservationCode) {
        releases.release(ReleaseReservationCommand.from(reservationCode));
    }

    /** Chuyển mã reservation sang use case đánh dấu bắt đầu sử dụng xe. */
    @Override
    public void markInUse(String reservationCode) {
        departures.markInUse(MarkReservationInUseCommand.from(reservationCode));
    }

    /** Chuyển mã reservation sang use case hoàn tất; khoảng đệm vẫn do domain bảo vệ. */
    @Override
    public void complete(String reservationCode) {
        completions.complete(CompleteReservationCommand.from(reservationCode));
    }

    /** Chỉ gọi khi giấy tờ được gia hạn; chuyển nguyên mã và mốc mới sang use case BR-015. */
    @Override
    public void moveComplianceHoldStart(String reservationCode, Instant newStartInclusive) {
        complianceMoves.moveComplianceHoldStart(MoveComplianceHoldStartCommand.from(reservationCode, newStartInclusive));
    }

    /** Tạo query từ khoảng chưa cộng đệm và tập ứng viên; không giữ chỗ hoặc kiểm lại khi ghi. */
    @Override
    public Set<Long> findBusyVehicleIds(Period period, Duration buffer, Collection<Long> candidateIds) {
        Period checked = Validations.required(period, "period");
        return queries.listBusyVehicleIds(ListBusyVehiclesQuery.from(
                checked.startInclusive(), checked.endExclusive(), buffer, candidateIds));
    }

    /** Ánh xạ tường minh năm nguyên nhân khóa; thêm giá trị API mới buộc bổ sung ánh xạ khi biên dịch. */
    private static ReservationKind toReservationKind(BlockKind kind) {
        return switch (kind) {
            case MAINTENANCE -> ReservationKind.MAINTENANCE;
            case INSPECTION -> ReservationKind.INSPECTION;
            case TRANSFER -> ReservationKind.TRANSFER;
            case OWNER_BLOCK -> ReservationKind.OWNER_BLOCK;
            case COMPLIANCE_HOLD -> ReservationKind.COMPLIANCE_HOLD;
        };
    }
}
