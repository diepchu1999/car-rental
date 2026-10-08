package com.carrental.branch.adapter.in.rest.admin;

import com.carrental.branch.adapter.in.rest.admin.request.CreateBranchRequest;
import com.carrental.branch.adapter.in.rest.admin.response.BranchResponse;
import com.carrental.branch.application.command.CreateBranchCommand;
import com.carrental.branch.application.port.in.CreateBranchUseCase;
import com.carrental.branch.application.port.in.GetBranchUseCase;
import com.carrental.branch.application.query.GetBranchQuery;
import com.carrental.branch.application.view.BranchDetail;
import com.carrental.shared.api.ApiResponse;
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
 * Cung cấp API quản trị để tạo và đọc thông tin chi nhánh.
 *
 * <p>Vị trí chi nhánh phục vụ BR-003.
 * Controller chỉ chuyển đổi dữ liệu HTTP và gọi các use case,
 * không truy cập cơ sở dữ liệu hoặc kiểm tra quy tắc nghiệp vụ.
 *
 * <p>Namespace admin xác định nhóm API, không tự cung cấp phân quyền.
 * Xác thực và phân quyền được hiện thực ở task riêng.
 */
@RestController
@RequestMapping(
        path = AdminBranchController.BASE_PATH,
        produces = MediaType.APPLICATION_JSON_VALUE
)
class AdminBranchController {

    static final String BASE_PATH = "/api/v1/admin/branches";

    private final CreateBranchUseCase createBranchUseCase;

    private final GetBranchUseCase getBranchUseCase;

    /**
     * Nhận các cổng đầu vào do Spring cung cấp.
     *
     * <p>Controller không phụ thuộc lớp service cụ thể
     * hoặc các cổng truy cập dữ liệu.
     *
     * @param createBranchUseCase chức năng tạo chi nhánh
     * @param getBranchUseCase chức năng đọc chi nhánh theo mã nghiệp vụ
     */
    AdminBranchController(
            CreateBranchUseCase createBranchUseCase,
            GetBranchUseCase getBranchUseCase
    ) {
        this.createBranchUseCase = createBranchUseCase;
        this.getBranchUseCase = getBranchUseCase;
    }

    /**
     * Nhận tọa độ, tạo chi nhánh và trả thông tin tài nguyên vừa tạo.
     *
     * <p>Command factory chịu trách nhiệm kiểm tra đầu vào.
     * Use case sinh mã, lưu dữ liệu và đọc lại kết quả.
     *
     * <p>Header Location chứa đường dẫn đọc chi nhánh theo mã nghiệp vụ,
     * không chứa khóa chính số hoặc địa chỉ máy chủ viết cứng.
     *
     * <p>Các exception được chuyển thành response lỗi
     * bởi cơ chế xử lý lỗi API dùng chung.
     *
     * @param request dữ liệu JSON của yêu cầu tạo chi nhánh
     * @return HTTP 201, header Location và response thành công
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<BranchResponse>> create(
            @RequestBody CreateBranchRequest request
    ) {
        CreateBranchCommand command = CreateBranchCommand.from(
                request.latitude(),
                request.longitude(),
                request.name(),
                request.address()
        );

        BranchDetail view = createBranchUseCase.create(command);
        BranchResponse response = BranchResponse.fromDomain(view);

        URI location = URI.create(
                BASE_PATH + "/" + response.code()
        );

        return ResponseEntity.created(location)
                .body(ApiResponse.success(response));
    }

    /**
     * Đọc thông tin chi nhánh theo mã nghiệp vụ trên URL.
     *
     * <p>Query giữ nguyên mã đầu vào. Use case chịu trách nhiệm
     * báo BRANCH_NOT_FOUND nếu chi nhánh không tồn tại.
     *
     * <p>Response chỉ chứa các trường thuộc hợp đồng HTTP,
     * không trả application view trực tiếp.
     *
     * @param code mã nghiệp vụ của chi nhánh cần đọc
     * @return response thành công với HTTP 200
     */
    @GetMapping("/{code}")
    public ApiResponse<BranchResponse> get(
            @PathVariable("code") String code
    ) {
        GetBranchQuery query = GetBranchQuery.from(code);

        BranchDetail view = getBranchUseCase.get(query);
        BranchResponse response = BranchResponse.fromDomain(view);

        return ApiResponse.success(response);
    }
}
