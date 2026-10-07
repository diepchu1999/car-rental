package com.carrental.vehicle.adapter.in.rest.admin;

import com.carrental.shared.api.ApiResponse;
import com.carrental.vehicle.adapter.in.rest.admin.request.CreateVehicleRequest;
import com.carrental.vehicle.adapter.in.rest.admin.response.VehicleResponse;
import com.carrental.vehicle.application.command.ApproveVehicleCommand;
import com.carrental.vehicle.application.command.CreateVehicleCommand;
import com.carrental.vehicle.application.command.SubmitVehicleForApprovalCommand;
import com.carrental.vehicle.application.port.in.ApproveVehicleUseCase;
import com.carrental.vehicle.application.port.in.CreateVehicleUseCase;
import com.carrental.vehicle.application.port.in.GetVehicleUseCase;
import com.carrental.vehicle.application.port.in.SubmitVehicleForApprovalUseCase;
import com.carrental.vehicle.application.query.GetVehicleQuery;
import com.carrental.vehicle.domain.OwnershipType;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Cung cấp API tạo, đọc, gửi duyệt và duyệt xe theo BR-003, BR-005, BR-010.
 *
 * <p>Chỉ chuyển đổi dữ liệu HTTP và gọi port đầu vào, không truy cập JDBC.
 * API GĐ1 cố định COMPANY, không rẽ nhánh theo loại sở hữu.
 * Namespace admin chưa có xác thực hoặc phân quyền trong Task 4.
 */
@RestController
@RequestMapping(path = AdminVehicleController.BASE_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
class AdminVehicleController {

    static final String BASE_PATH = "/api/v1/admin/vehicles";

    private final CreateVehicleUseCase createVehicleUseCase;
    private final GetVehicleUseCase getVehicleUseCase;
    private final SubmitVehicleForApprovalUseCase submitVehicleUseCase;
    private final ApproveVehicleUseCase approveVehicleUseCase;

    /**
     * Nhận các chức năng ứng dụng qua interface.
     *
     * @param createVehicleUseCase tạo xe bản nháp
     * @param getVehicleUseCase đọc xe theo mã
     * @param submitVehicleUseCase gửi duyệt
     * @param approveVehicleUseCase phê duyệt
     */
    AdminVehicleController(CreateVehicleUseCase createVehicleUseCase,
                           GetVehicleUseCase getVehicleUseCase,
                           SubmitVehicleForApprovalUseCase submitVehicleUseCase,
                           ApproveVehicleUseCase approveVehicleUseCase) {
        this.createVehicleUseCase = createVehicleUseCase;
        this.getVehicleUseCase = getVehicleUseCase;
        this.submitVehicleUseCase = submitVehicleUseCase;
        this.approveVehicleUseCase = approveVehicleUseCase;
    }

    /**
     * Tạo xe COMPANY ở DRAFT và trả Location theo mã nghiệp vụ.
     *
     * @param request dữ liệu JSON chưa kiểm tra
     * @return HTTP 201 cùng response xe vừa tạo
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<VehicleResponse>> create(@RequestBody CreateVehicleRequest request) {
        CreateVehicleCommand command = CreateVehicleCommand.from(
                request.plateNumber(), OwnershipType.COMPANY, request.fuelType(),
                request.branchCode(), request.inspectionExpiresOn(), request.liabilityInsuranceExpiresOn(),
                request.seats(), request.transmission(), request.make(), request.model()
        );
        VehicleResponse response = VehicleResponse.fromDomain(createVehicleUseCase.create(command));
        return ResponseEntity.created(URI.create(BASE_PATH + "/" + response.code()))
                .body(ApiResponse.success(response));
    }

    /**
     * Đọc xe theo mã, không áp lại điều kiện giấy tờ của bước duyệt.
     *
     * @param code mã nghiệp vụ trên URL
     * @return HTTP 200 cùng dữ liệu xe
     */
    @GetMapping("/{code}")
    public ApiResponse<VehicleResponse> get(@PathVariable("code") String code) {
        return ApiResponse.success(VehicleResponse.fromDomain(
                getVehicleUseCase.get(GetVehicleQuery.from(code))
        ));
    }

    /**
     * Gửi xe vào trạng thái chờ duyệt, không nhận trạng thái đích từ client.
     *
     * @param code mã xe
     * @return HTTP 200 cùng hồ sơ sau khi gửi duyệt
     */
    @PostMapping("/{code}/submit-for-approval")
    public ApiResponse<VehicleResponse> submitForApproval(@PathVariable("code") String code) {
        return ApiResponse.success(VehicleResponse.fromDomain(
                submitVehicleUseCase.submitForApproval(SubmitVehicleForApprovalCommand.from(code))
        ));
    }

    /**
     * Duyệt xe theo ngày server, không nhận ngày duyệt hoặc trạng thái từ client.
     *
     * @param code mã xe
     * @return HTTP 200 cùng hồ sơ sau khi duyệt
     */
    @PostMapping("/{code}/approve")
    public ApiResponse<VehicleResponse> approve(@PathVariable("code") String code) {
        return ApiResponse.success(VehicleResponse.fromDomain(
                approveVehicleUseCase.approve(ApproveVehicleCommand.from(code))
        ));
    }
}
