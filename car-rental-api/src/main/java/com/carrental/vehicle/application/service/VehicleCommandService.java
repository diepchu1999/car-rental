package com.carrental.vehicle.application.service;

import com.carrental.branch.api.BranchDirectory;
import com.carrental.branch.api.BranchRef;
import com.carrental.shared.code.BusinessCodeGenerator;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.vehicle.application.command.CreateVehicleCommand;
import com.carrental.vehicle.application.command.ApproveVehicleCommand;
import com.carrental.vehicle.application.command.SubmitVehicleForApprovalCommand;
import com.carrental.vehicle.application.port.in.ApproveVehicleUseCase;
import com.carrental.vehicle.application.port.in.SubmitVehicleForApprovalUseCase;
import com.carrental.vehicle.application.port.in.CreateVehicleUseCase;
import com.carrental.vehicle.application.port.out.ReadVehiclePort;
import com.carrental.vehicle.application.port.out.VehiclePlateConflictException;
import com.carrental.vehicle.application.port.out.WriteVehiclePort;
import com.carrental.vehicle.application.view.VehicleDetail;
import com.carrental.vehicle.domain.Vehicle;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;

/**
 * Điều phối các thao tác ghi hồ sơ xe.
 *
 * <p>Luồng tạo xe liên kết chi nhánh theo BR-003, giữ loại sở hữu
 * theo BR-001 và tạo bản nháp theo vòng đời xe tại status-flow mục 4.
 *
 * <p>Điều kiện giấy tờ của BR-005 được kiểm khi duyệt,
 * không áp dụng vào bước tạo bản nháp.
 *
 * <p>Service sở hữu transaction. Việc ghi và đọc lại kết quả
 * nằm trong cùng giao dịch theo module-architecture mục 10.
 */
@Service
class VehicleCommandService implements CreateVehicleUseCase,
        SubmitVehicleForApprovalUseCase, ApproveVehicleUseCase {

    private static final String CODE_PREFIX = "XE";

    /**
     * Giới hạn kỹ thuật: tối đa năm lần thử, tính cả lần đầu tiên.
     *
     * <p>Ngăn vòng lặp vô hạn nếu liên tục sinh mã đã tồn tại.
     * Đây không phải giới hạn nghiệp vụ về số lượng xe.
     */
    private static final int MAX_CODE_GENERATION_ATTEMPTS = 5;

    private final WriteVehiclePort writeVehiclePort;

    private final ReadVehiclePort readVehiclePort;

    private final BusinessCodeGenerator businessCodeGenerator;

    private final BranchDirectory branchDirectory;

    private final Clock clock;

    /**
     * Nhận các cổng lưu trữ, bộ sinh mã và cổng tra cứu chi nhánh.
     *
     * <p>Phụ thuộc chéo sang module branch chỉ đi qua package api.
     * Service không truy cập adapter hoặc bảng của module branch.
     *
     * @param writeVehiclePort cổng chèn và cập nhật xe
     * @param readVehiclePort cổng đọc dữ liệu xe
     * @param businessCodeGenerator bộ sinh mã nghiệp vụ
     * @param branchDirectory cổng tra cứu chi nhánh giữa các module
     * @param clock đồng hồ dùng chung, xác định ngày duyệt theo múi giờ cấu hình
     */
    VehicleCommandService(
            WriteVehiclePort writeVehiclePort,
            ReadVehiclePort readVehiclePort,
            BusinessCodeGenerator businessCodeGenerator,
            BranchDirectory branchDirectory,
            Clock clock
    ) {
        this.writeVehiclePort = writeVehiclePort;
        this.readVehiclePort = readVehiclePort;
        this.businessCodeGenerator = businessCodeGenerator;
        this.branchDirectory = branchDirectory;
        this.clock = clock;
    }

    /**
     * Tạo hồ sơ xe bản nháp gắn với chi nhánh đã tồn tại.
     *
     * <p>Tra cứu chi nhánh một lần trước khi sinh mã và chèn xe.
     * Loại sở hữu được truyền nguyên từ command, không phân nhánh.
     * API giai đoạn 1 sẽ cung cấp cố định COMPANY.
     *
     * <p>Chỉ thử mã khác khi cổng ghi trả false do trùng mã nghiệp vụ.
     * Trùng biển số được chuyển thành lỗi xung đột và dừng ngay.
     * Các lỗi lưu trữ khác được truyền ra ngoài.
     *
     * <p>Không kiểm tra mã hoặc biển số tồn tại trước khi chèn.
     * Ràng buộc UNIQUE trong database quyết định việc chèn có hợp lệ.
     *
     * <p>Nếu chèn thành công nhưng không đọc lại được,
     * đây là lỗi nội bộ và transaction phải rollback.
     *
     * @param command yêu cầu tạo xe đã được kiểm tra, không được null
     * @return thông tin xe được đọc lại sau khi chèn thành công
     * @throws DomainException nếu chi nhánh không tồn tại
     *                         hoặc biển số đã được sử dụng
     * @throws IllegalStateException nếu hết lượt thử mã
     *                               hoặc không đọc lại được xe vừa chèn
     */
    @Override
    @Transactional
    public VehicleDetail create(CreateVehicleCommand command) {
        BranchRef branch = branchDirectory.findByCode(command.branchCode())
                .orElseThrow(() ->
                        DomainException.notFound(ErrorCode.BRANCH_NOT_FOUND));

        for (int attempt = 0;
             attempt < MAX_CODE_GENERATION_ATTEMPTS;
             attempt++) {

            String code = businessCodeGenerator.generate(CODE_PREFIX);

            Vehicle vehicle = Vehicle.createDraft(
                    code,
                    command.plateNumber(),
                    command.ownershipType(),
                    command.fuelType(),
                    branch.id(),
                    command.documents(),
                    command.specifications()
            );

            boolean inserted;

            try {
                inserted = writeVehiclePort.insert(vehicle);
            } catch (VehiclePlateConflictException failure) {
                throw DomainException.conflict(
                        ErrorCode.VEHICLE_PLATE_ALREADY_EXISTS
                );
            }

            if (!inserted) {
                continue;
            }

            return readVehiclePort.findByCode(code)
                    .map(detail -> detail.withBranchCode(branch.code()))
                    .orElseThrow(() -> new IllegalStateException(
                            "Created vehicle could not be reloaded: " + code
                    ));
        }

        throw new IllegalStateException(
                "Failed to create a vehicle with a unique code after "
                        + MAX_CODE_GENERATION_ATTEMPTS
                        + " attempts."
        );
    }

    /**
     * Gửi hồ sơ từ DRAFT sang PENDING_APPROVAL theo status-flow mục 4.
     *
     * @param command yêu cầu gửi duyệt đã kiểm tra đầu vào
     * @return dữ liệu đọc lại sau cập nhật
     * @throws DomainException nếu không tìm thấy, sai trạng thái hoặc có tranh chấp
     */
    @Override
    @Transactional
    public VehicleDetail submitForApproval(SubmitVehicleForApprovalCommand command) {
        Vehicle original = loadVehicle(command.code());
        Vehicle submitted = original.submitForApproval();
        return saveTransition(original, submitted);
    }

    /**
     * Duyệt xe theo BR-010 và kiểm giấy tờ tại ngày duyệt theo BR-005.
     *
     * <p>Ngày duyệt lấy từ đồng hồ server, không nhận từ request.
     * Domain quyết định chuyển trạng thái; service chỉ điều phối lưu trữ.
     *
     * @param command yêu cầu phê duyệt đã kiểm tra đầu vào
     * @return dữ liệu đọc lại sau cập nhật
     * @throws DomainException nếu không tìm thấy, sai trạng thái, giấy tờ lỗi hoặc tranh chấp
     */
    @Override
    @Transactional
    public VehicleDetail approve(ApproveVehicleCommand command) {
        Vehicle original = loadVehicle(command.code());
        Vehicle approved = original.approve(LocalDate.now(clock));
        return saveTransition(original, approved);
    }

    /**
     * Tải aggregate phục vụ thao tác nghiệp vụ qua cổng đọc chuyên biệt.
     *
     * <p>Không phụ thuộc hình dạng VehicleDetail để khôi phục xe.
     * Chỉ kết quả rỗng được chuyển thành lỗi không tìm thấy;
     * lỗi từ cổng đọc được truyền nguyên trạng.
     *
     * @param code mã xe cần thao tác
     * @return aggregate ở trạng thái đang được lưu
     * @throws DomainException nếu xe không tồn tại
     */
    private Vehicle loadVehicle(String code) {
        return readVehiclePort.loadAggregate(code)
                .orElseThrow(() ->
                        DomainException.notFound(ErrorCode.VEHICLE_NOT_FOUND));
    }

    /**
     * Ghi trạng thái mới chỉ khi trạng thái cũ còn khớp trong cùng câu UPDATE.
     *
     * <p>Không thử lại tự động khi trạng thái đã thay đổi. Client phải đọc lại xe.
     * Sai trạng thái ngay lúc đọc là lỗi 422 từ domain; thua tranh chấp là 409.
     * Việc đọc lại thất bại làm rollback cập nhật trong transaction hiện tại.
     *
     * @param original xe trước khi gọi hành vi domain
     * @param changed xe sau khi domain chấp nhận chuyển trạng thái
     * @return dữ liệu đọc lại sau ghi
     */
    private VehicleDetail saveTransition(Vehicle original, Vehicle changed) {
        if (!writeVehiclePort.updateStatus(original.code(), original.status(), changed.status())) {
            throw DomainException.conflict(ErrorCode.VEHICLE_INVALID_STATUS_TRANSITION);
        }
        return readVehiclePort.findByCode(original.code())
                .map(detail -> VehicleDetailEnricher.enrich(detail, branchDirectory))
                .orElseThrow(() -> new IllegalStateException(
                        "Updated vehicle could not be reloaded: " + original.code()
                ));
    }
}
