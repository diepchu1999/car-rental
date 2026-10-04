package com.carrental.availability.application.service;

import com.carrental.availability.api.ReservationRef;
import com.carrental.availability.application.command.HoldReservationCommand;
import com.carrental.availability.application.command.BlockReservationCommand;
import com.carrental.availability.application.command.ConfirmReservationCommand;
import com.carrental.availability.application.command.ReleaseReservationCommand;
import com.carrental.availability.application.command.MarkReservationInUseCommand;
import com.carrental.availability.application.command.CompleteReservationCommand;
import com.carrental.availability.application.command.MoveComplianceHoldStartCommand;
import com.carrental.availability.application.port.in.HoldReservationUseCase;
import com.carrental.availability.application.port.in.BlockReservationUseCase;
import com.carrental.availability.application.port.in.ConfirmReservationUseCase;
import com.carrental.availability.application.port.in.ReleaseReservationUseCase;
import com.carrental.availability.application.port.in.MarkReservationInUseUseCase;
import com.carrental.availability.application.port.in.CompleteReservationUseCase;
import com.carrental.availability.application.port.in.MoveComplianceHoldStartUseCase;
import com.carrental.availability.application.port.in.ExpireReservationHoldsUseCase;
import com.carrental.availability.application.port.out.ReadReservationPort;
import com.carrental.availability.application.port.out.ReadReservationPolicyPort;
import com.carrental.availability.application.port.out.ReservationOverlapException;
import com.carrental.availability.application.port.out.WriteReservationPort;
import com.carrental.availability.domain.Reservation;
import com.carrental.availability.domain.ReservationPeriod;
import com.carrental.shared.code.BusinessCodeGenerator;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.shared.validation.Validations;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;

/**
 * Điều phối giữ chỗ và vòng đời khóa theo BR-102, BR-103, BR-104,
 * BR-109, BR-116, BR-225, BR-304 và status-flow §2; khóa vận hành theo BR-007, BR-011, BR-012, BR-015.
 *
 * <p>Khi giữ chỗ, đọc Clock và chính sách một lần, không đọc kiểm xe trống trước khi ghi.
 * Chỉ thử lại khi trùng mã KL theo database-guideline §2. Khi chuyển trạng thái,
 * tải aggregate để domain kiểm điều kiện, lấy Clock một lần và không đọc lại chính sách.
 * Service không biết JDBC, loại gói thuê hoặc loại sở hữu.
 */
@Service
class ReservationCommandService implements HoldReservationUseCase, BlockReservationUseCase, ConfirmReservationUseCase,
        ReleaseReservationUseCase, MarkReservationInUseUseCase, CompleteReservationUseCase,
        ExpireReservationHoldsUseCase, MoveComplianceHoldStartUseCase {

    private static final String CODE_PREFIX = "KL";

    /** Giới hạn kỹ thuật gồm cả lần đầu, tránh thử mã vô hạn. */
    private static final int MAX_CODE_GENERATION_ATTEMPTS = 5;

    private final WriteReservationPort writePort;
    private final ReadReservationPort readPort;
    private final ReadReservationPolicyPort policyPort;
    private final BusinessCodeGenerator codeGenerator;
    private final Clock clock;

    /**
     * Nhận các cổng đầu ra, bộ sinh mã chung và đúng Clock của ứng dụng.
     *
     * @param writePort cổng ghi khóa lịch
     * @param readPort cổng tải aggregate để kiểm chuyển trạng thái, không kiểm xe trống
     * @param policyPort nguồn chính sách có hợp đồng đọc theo mốc thời gian
     * @param codeGenerator bộ sinh mã nghiệp vụ dùng chung
     * @param clock đồng hồ applicationClock dùng chung
     */
    ReservationCommandService(
            WriteReservationPort writePort,
            ReadReservationPort readPort,
            ReadReservationPolicyPort policyPort,
            BusinessCodeGenerator codeGenerator,
            @Qualifier("applicationClock") Clock clock
    ) {
        this.writePort = writePort;
        this.readPort = readPort;
        this.policyPort = policyPort;
        this.codeGenerator = codeGenerator;
        this.clock = clock;
    }

    /**
     * Nhả giữ chỗ đến hạn theo BR-103 bằng một cập nhật có điều kiện trong transaction.
     *
     * <p>Lấy applicationClock đúng một lần; không đọc lại chính sách, không đọc
     * trước danh sách khóa. Port bảo vệ đồng thời bằng điều kiện HELD và hạn đã lưu.
     * Lỗi được truyền ra để rollback cả lượt, scheduler sẽ chạy lượt kế tiếp.
     *
     * @return số bản ghi vừa chuyển sang RELEASED, chưa cam kết transaction ngoài đã commit
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public int expireHolds() {
        return writePort.releaseExpiredHolds(clock.instant());
    }

    /**
     * Cộng đệm, đóng băng hạn giữ chỗ và ghi trong transaction REQUIRED.
     *
     * <p>Hạn được domain tính một lần. Khi trùng mã chỉ thay mã, không đọc
     * lại Clock, đọc lại chính sách, cộng lại đệm hoặc kéo dài hạn.
     * Trùng lịch báo VEHICLE_NOT_AVAILABLE ngay, không thử mã khác.
     * Các lỗi lưu trữ khác được truyền nguyên để transaction rollback.
     *
     * @param command yêu cầu giữ chỗ đã kiểm tra
     * @return định danh từ ID do INSERT RETURNING trả và mã vừa ghi
     * @throws DomainException nếu đầu vào sai hoặc xe đã bận
     * @throws IllegalStateException nếu hết năm lần thử mã
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public ReservationRef hold(HoldReservationCommand command) {
        HoldReservationCommand checked = Validations.required(command, "command");
        ReservationPeriod period = checked.rentalPeriod().withBuffer(checked.buffer());
        Instant createdAt = clock.instant();
        Duration duration = policyPort.holdDuration(createdAt);
        Reservation reservation = Reservation.createHeld(
                codeGenerator.generate(CODE_PREFIX), checked.vehicleId(), period,
                checked.bookingCode(), duration, createdAt
        );
        return insertWithCodeRetries(reservation);
    }

    /**
     * Tạo BLOCKED với đúng khoảng và lý do đã nhận theo BR-007, BR-011, BR-012, BR-015, BR-104.
     *
     * <p>Không tra xe trống, không đọc chính sách TTL và không cộng đệm thuê xe.
     * Chỉ lấy Clock một lần; mọi lần thử lại mã dùng nguyên các trường còn lại.
     *
     * @param command yêu cầu vận hành đã kiểm tra
     * @return định danh khóa mới
     * @throws DomainException nếu thiếu command hoặc xe đã bận
     * @throws IllegalStateException nếu hết năm lần thử mã
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public ReservationRef block(BlockReservationCommand command) {
        BlockReservationCommand checked = Validations.required(command, "command");
        Instant createdAt = clock.instant();
        Reservation reservation = Reservation.createBlocked(
                codeGenerator.generate(CODE_PREFIX), checked.vehicleId(), checked.period(),
                checked.kind(), checked.reason(), createdAt
        );
        return insertWithCodeRetries(reservation);
    }

    /**
     * Dùng chung cơ chế chèn cho giữ chỗ và khóa vận hành, chỉ thử lại khi trùng mã KL.
     *
     * <p>OptionalLong rỗng chỉ biểu diễn uq_reservation_code. Xung đột lịch phải dừng ngay,
     * không thử tiếp trong transaction đã lỗi. SQL vẫn giữ ON CONFLICT có tên constraint.
     *
     * @param reservation aggregate đã có mã và các mốc thời gian cố định
     * @return định danh lấy từ INSERT RETURNING
     * @throws DomainException nếu ràng buộc chống chồng lịch từ chối
     * @throws IllegalStateException nếu hết lượt thử mã
     */
    private ReservationRef insertWithCodeRetries(Reservation reservation) {
        for (int attempt = 0; attempt < MAX_CODE_GENERATION_ATTEMPTS; attempt++) {
            OptionalLong id;
            try {
                id = writePort.insert(reservation);
            } catch (ReservationOverlapException failure) {
                throw DomainException.conflict(ErrorCode.VEHICLE_NOT_AVAILABLE);
            }

            if (id.isPresent()) {
                return new ReservationRef(id.getAsLong(), reservation.code());
            }

            if (attempt + 1 < MAX_CODE_GENERATION_ATTEMPTS) {
                reservation = withNextCode(reservation);
            }
        }

        throw new IllegalStateException(
                "Failed to create a reservation with a unique code after "
                        + MAX_CODE_GENERATION_ATTEMPTS + " attempts."
        );
    }

    /**
     * Xác nhận HELD còn hạn theo BR-103; kiểm mốc hiện tại với hạn đã lưu.
     *
     * @param command mã khóa lịch cần xác nhận
     * @throws DomainException nếu đầu vào sai, không tìm thấy, hết hạn, sai trạng thái hoặc thua tranh chấp
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void confirm(ConfirmReservationCommand command) {
        Reservation original = loadReservation(Validations.required(command, "command").code());
        saveTransition(original, original.confirm(clock.instant()));
    }

    /**
     * Nhả HELD hoặc CONFIRMED theo BR-103, BR-304; không dùng để hoàn tất khóa vận hành.
     *
     * @param command mã khóa lịch cần nhả
     * @throws DomainException nếu đầu vào sai, không tìm thấy, sai trạng thái hoặc thua tranh chấp
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void release(ReleaseReservationCommand command) {
        Reservation original = loadReservation(Validations.required(command, "command").code());
        saveTransition(original, original.release(clock.instant()));
    }

    /**
     * Đánh dấu CONFIRMED thành IN_USE theo status-flow §2; điều kiện bàn giao thuộc bên gọi.
     *
     * @param command mã khóa lịch đã được bàn giao
     * @throws DomainException nếu đầu vào sai, không tìm thấy, sai trạng thái hoặc thua tranh chấp
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void markInUse(MarkReservationInUseCommand command) {
        Reservation original = loadReservation(Validations.required(command, "command").code());
        saveTransition(original, original.markInUse(clock.instant()));
    }

    /**
     * Hoàn tất IN_USE hoặc khóa vận hành hữu hạn BLOCKED, không co khoảng để bảo toàn đệm BR-109, BR-116.
     * Khóa COMPLIANCE_HOLD bị domain từ chối theo BR-015.
     *
     * @param command mã khóa lịch đã xong chuyến hoặc công việc vận hành
     * @throws DomainException nếu đầu vào sai, không tìm thấy, sai trạng thái hoặc thua tranh chấp
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void complete(CompleteReservationCommand command) {
        Reservation original = loadReservation(Validations.required(command, "command").code());
        saveTransition(original, original.complete(clock.instant()));
    }

    /**
     * Dời mốc trên chính khóa giấy tờ theo BR-015, chỉ gọi khi giấy tờ được gia hạn.
     * Không đọc Clock/chính sách hoặc sinh mã; không thử lại khi CAS thua tranh chấp.
     *
     * @param command mã khóa và mốc mới sau mốc cũ
     * @throws DomainException nếu dữ liệu sai, không tìm thấy, sai loại/trạng thái hoặc mốc đã đổi
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void moveComplianceHoldStart(MoveComplianceHoldStartCommand command) {
        MoveComplianceHoldStartCommand checked = Validations.required(command, "command");
        Reservation original = loadReservation(checked.code());
        Reservation changed = original.moveComplianceHoldStart(checked.newStartInclusive());
        if (!writePort.moveComplianceHoldStart(original.code(), original.period().startInclusive(),
                changed.period().startInclusive())) {
            throw DomainException.conflict(ErrorCode.RESERVATION_INVALID_STATUS_TRANSITION);
        }
    }

    /**
     * Tải aggregate đúng mã reservation; chỉ kết quả rỗng mới là không tìm thấy.
     *
     * @param code mã đã kiểm tra ở command
     * @return khóa lịch tại thời điểm đọc, chưa bảo đảm không bị transaction khác thay đổi
     */
    private Reservation loadReservation(String code) {
        return readPort.loadAggregate(code)
                .orElseThrow(() -> DomainException.notFound(ErrorCode.RESERVATION_NOT_FOUND));
    }

    /**
     * Ghi kết quả domain với điều kiện trạng thái cũ, không thử lại hoặc đọc lại khi thua tranh chấp.
     *
     * <p>Sai cạnh lúc đọc là RULE_VIOLATION từ domain; không còn khớp lúc ghi là CONFLICT.
     * Không đọc chính sách, sinh mã hay đổi hạn của khóa đã tồn tại.
     *
     * @param original aggregate trước chuyển
     * @param changed aggregate đã được domain chấp nhận
     */
    private void saveTransition(Reservation original, Reservation changed) {
        if (!writePort.updateStatus(original.code(), original.status(), changed.status(), changed.statusChangedAt())) {
            throw DomainException.conflict(ErrorCode.RESERVATION_INVALID_STATUS_TRANSITION);
        }
    }

    /**
     * Chỉ thay mã sau xung đột UNIQUE, giữ nguyên toàn bộ dữ liệu đã đóng băng.
     *
     * @param reservation khóa lịch của lần thử trước
     * @return bản có mã mới và cùng thời hạn, khoảng khóa, xe, mã đơn
     */
    private Reservation withNextCode(Reservation reservation) {
        return Reservation.restore(
                codeGenerator.generate(CODE_PREFIX), reservation.vehicleId(), reservation.period(),
                reservation.kind(), reservation.status(), reservation.bookingCode(), reservation.reason(),
                reservation.holdExpiresAt(), reservation.createdAt(), reservation.statusChangedAt()
        );
    }
}
