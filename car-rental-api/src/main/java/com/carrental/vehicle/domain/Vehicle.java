package com.carrental.vehicle.domain;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.shared.validation.Validations;

import java.time.LocalDate;
import java.util.regex.Pattern;

/**
 * Biểu diễn xe và bảo vệ các bước gửi duyệt, phê duyệt.
 *
 * <p>Loại sở hữu tuân theo BR-001, liên kết chi nhánh theo BR-003,
 * giấy tờ theo BR-005, duyệt xe theo BR-010
 * và loại nhiên liệu theo BR-410.
 *
 * <p>Đối tượng bất biến. Mỗi thao tác chuyển trạng thái trả về
 * một đối tượng mới, giữ nguyên danh tính và loại sở hữu.
 *
 * <p>Domain không sinh mã, không truy cập database và không
 * kiểm tra chi nhánh tồn tại. Application thực hiện tra cứu
 * qua BranchDirectory trước khi ghi dữ liệu.
 *
 * <p>Không rẽ nhánh theo loại sở hữu. Điều kiện xe COMPANY
 * phải có chi nhánh được bảo vệ bằng ràng buộc database.
 * API tạo xe giai đoạn 1 chỉ cung cấp loại sở hữu COMPANY.
 *
 * <p>Khóa chính của xe do database sinh không nằm trong aggregate.
 * Mã nghiệp vụ là định danh dùng cho các thao tác hiện tại.
 */
public final class Vehicle {

    private static final Pattern CODE_PATTERN =
            Pattern.compile("XE-[A-Z0-9]{6}");

    private final String code;

    private final String plateNumber;

    private final OwnershipType ownershipType;

    private final FuelType fuelType;

    private final Long branchId;

    private final VehicleStatus status;

    private final VehicleDocuments documents;

    private final VehicleSpecifications specifications;

    /**
     * Khởi tạo dữ liệu xe với các kiểm tra cấu trúc dùng chung.
     *
     * <p>Constructor được giữ private để phân biệt rõ đường tạo mới
     * với đường khôi phục dữ liệu đã lưu.
     *
     * <p>Không kiểm hạn giấy tờ tại đây vì phải đọc được cả hồ sơ
     * chưa đủ giấy tờ hoặc đã hết hạn sau thời điểm được duyệt.
     *
     * <p>Biển số được giữ nguyên. Chưa tự đặt quy tắc định dạng,
     * cắt khoảng trắng hoặc chuyển đổi chữ hoa, chữ thường.
     *
     * @param code mã xe theo mẫu XE- và sáu chữ cái ASCII viết hoa hoặc chữ số
     * @param plateNumber biển số có nội dung
     * @param ownershipType loại sở hữu của xe
     * @param fuelType loại nhiên liệu của xe
     * @param branchId định danh chi nhánh, nếu có phải lớn hơn không
     * @param status trạng thái được cung cấp bởi đường khởi tạo tương ứng
     * @param documents thông tin hạn giấy tờ, không được null
     * @param specifications thuộc tính xe bắt buộc theo BR-018
     * @throws DomainException nếu dữ liệu không đáp ứng cấu trúc yêu cầu
     */
    private Vehicle(
            String code,
            String plateNumber,
            OwnershipType ownershipType,
            FuelType fuelType,
            Long branchId,
            VehicleStatus status,
            VehicleDocuments documents,
            VehicleSpecifications specifications
    ) {
        this.code = Validations.requiredText(code, "code");

        if (!CODE_PATTERN.matcher(this.code).matches()) {
            throw DomainException.invalidInput(
                    "code must contain XE- followed by six uppercase ASCII letters or digits."
            );
        }

        this.plateNumber = Validations.requiredText(
                plateNumber,
                "plateNumber"
        );
        this.ownershipType = Validations.required(
                ownershipType,
                "ownershipType"
        );
        this.fuelType = Validations.required(
                fuelType,
                "fuelType"
        );

        if (branchId != null && branchId <= 0L) {
            throw DomainException.invalidInput(
                    "branchId must be greater than zero when provided."
            );
        }

        this.branchId = branchId;
        this.status = Validations.required(status, "status");
        this.documents = Validations.required(documents, "documents");
        this.specifications = Validations.required(specifications, "specifications");
    }

    /**
     * Tạo hồ sơ xe mới ở trạng thái DRAFT.
     *
     * <p>Bên gọi không được chọn trạng thái ban đầu.
     * Mã nghiệp vụ do application sinh trước khi gọi phương thức này.
     *
     * <p>Đường tạo xe giai đoạn 1 cung cấp COMPANY và định danh
     * chi nhánh đã được application tra cứu.
     * Phương thức không tự phân nhánh theo loại sở hữu.
     *
     * @param code mã nghiệp vụ mới của xe
     * @param plateNumber biển số của xe
     * @param ownershipType loại sở hữu được tầng gọi cung cấp
     * @param fuelType loại nhiên liệu
     * @param branchId định danh chi nhánh liên kết
     * @param documents thông tin giấy tờ, có thể chứa ngày chưa được cung cấp
     * @param specifications thuộc tính xe bắt buộc theo BR-018
     * @return xe mới ở trạng thái DRAFT
     * @throws DomainException nếu dữ liệu không hợp lệ
     */
    public static Vehicle createDraft(
            String code,
            String plateNumber,
            OwnershipType ownershipType,
            FuelType fuelType,
            Long branchId,
            VehicleDocuments documents,
            VehicleSpecifications specifications
    ) {
        return new Vehicle(
                code,
                plateNumber,
                ownershipType,
                fuelType,
                branchId,
                VehicleStatus.DRAFT,
                documents,
                specifications
        );
    }

    /**
     * Khôi phục aggregate từ dữ liệu xe đã được lưu.
     *
     * <p>Giữ nguyên trạng thái đã lưu, không thực hiện lại thao tác duyệt
     * hoặc kiểm hạn giấy tờ theo ngày hiện tại.
     *
     * <p>Đây là đường khôi phục trạng thái nội bộ,
     * không dùng để nhận trạng thái tùy ý từ request tạo xe.
     *
     * @param code mã nghiệp vụ đã lưu
     * @param plateNumber biển số đã lưu
     * @param ownershipType loại sở hữu đã lưu
     * @param fuelType loại nhiên liệu đã lưu
     * @param branchId định danh chi nhánh đã lưu, có thể null
     * @param status trạng thái đã lưu
     * @param documents thông tin giấy tờ đã lưu
     * @param specifications thuộc tính xe đã lưu theo BR-018
     * @return aggregate biểu diễn dữ liệu đã lưu
     * @throws DomainException nếu dữ liệu không đáp ứng cấu trúc yêu cầu
     */
    public static Vehicle restore(
            String code,
            String plateNumber,
            OwnershipType ownershipType,
            FuelType fuelType,
            Long branchId,
            VehicleStatus status,
            VehicleDocuments documents,
            VehicleSpecifications specifications
    ) {
        return new Vehicle(
                code,
                plateNumber,
                ownershipType,
                fuelType,
                branchId,
                status,
                documents,
                specifications
        );
    }

    /**
     * Gửi hồ sơ bản nháp để chờ duyệt theo status-flow mục 4.
     *
     * <p>Chỉ xe DRAFT được gửi duyệt.
     * Việc kiểm đủ giấy tờ và còn hạn thuộc bước phê duyệt theo BR-005.
     *
     * @return bản mới của xe ở trạng thái PENDING_APPROVAL
     * @throws DomainException với VEHICLE_INVALID_STATUS_TRANSITION
     *                        nếu xe không ở trạng thái DRAFT
     */
    public Vehicle submitForApproval() {
        requireStatus(VehicleStatus.DRAFT);

        return withStatus(VehicleStatus.PENDING_APPROVAL);
    }

    /**
     * Phê duyệt xe đang chờ duyệt theo BR-010 và BR-005.
     *
     * <p>Kiểm trạng thái trước, sau đó kiểm giấy tờ có đủ và còn hạn.
     * Chỉ tạo bản ACTIVE khi tất cả điều kiện đều được đáp ứng.
     *
     * <p>Phân quyền người duyệt thuộc task xác thực và phân quyền,
     * không được tự suy ra từ việc gọi phương thức domain này.
     *
     * @param approvalDate ngày duyệt do application xác định
     * @return bản mới của xe ở trạng thái ACTIVE
     * @throws DomainException nếu trạng thái không cho phép duyệt,
     *                         thiếu giấy tờ hoặc giấy tờ đã hết hạn
     * @throws NullPointerException nếu thiếu ngày duyệt khi kiểm giấy tờ
     */
    public Vehicle approve(LocalDate approvalDate) {
        requireStatus(VehicleStatus.PENDING_APPROVAL);
        documents.validateForApproval(approvalDate);

        return withStatus(VehicleStatus.ACTIVE);
    }

    /**
     * Kiểm trạng thái hiện tại có cho phép thao tác được yêu cầu hay không.
     *
     * @param expectedStatus trạng thái bắt buộc trước khi thực hiện thao tác
     * @throws DomainException với VEHICLE_INVALID_STATUS_TRANSITION
     *                        nếu trạng thái hiện tại không khớp
     */
    private void requireStatus(VehicleStatus expectedStatus) {
        if (status != expectedStatus) {
            throw DomainException.ruleViolation(
                    ErrorCode.VEHICLE_INVALID_STATUS_TRANSITION
            );
        }
    }

    /**
     * Tạo bản mới chỉ thay đổi trạng thái, giữ nguyên các dữ liệu còn lại.
     *
     * <p>Phương thức private chỉ được gọi sau khi thao tác nghiệp vụ
     * đã kiểm tra điều kiện chuyển trạng thái.
     *
     * @param newStatus trạng thái đích của thao tác hợp lệ
     * @return bản mới của cùng xe với trạng thái được chỉ định
     */
    private Vehicle withStatus(VehicleStatus newStatus) {
        return new Vehicle(
                code,
                plateNumber,
                ownershipType,
                fuelType,
                branchId,
                newStatus,
                documents,
                specifications
        );
    }

    /**
     * Trả mã nghiệp vụ của xe.
     *
     * @return mã xe dùng để định danh trong các thao tác nghiệp vụ
     */
    public String code() {
        return code;
    }

    /**
     * Trả biển số đã được cung cấp, không tự chuẩn hóa.
     *
     * @return biển số của xe
     */
    public String plateNumber() {
        return plateNumber;
    }

    /**
     * Trả loại sở hữu để lưu hoặc truyền dữ liệu.
     *
     * @return loại sở hữu được giữ nguyên qua các thao tác chuyển trạng thái
     */
    public OwnershipType ownershipType() {
        return ownershipType;
    }

    /**
     * Trả loại nhiên liệu của xe theo BR-410.
     *
     * @return loại nhiên liệu đã được cung cấp
     */
    public FuelType fuelType() {
        return fuelType;
    }

    /**
     * Trả định danh chi nhánh liên kết.
     *
     * @return định danh chi nhánh, hoặc null nếu dữ liệu không có liên kết
     */
    public Long branchId() {
        return branchId;
    }

    /**
     * Trả trạng thái hiện tại của bản aggregate này.
     *
     * @return trạng thái vòng đời xe
     */
    public VehicleStatus status() {
        return status;
    }

    /**
     * Trả thông tin hạn giấy tờ của xe.
     *
     * @return đối tượng giấy tờ bất biến, không bao giờ null
     */
    public VehicleDocuments documents() {
        return documents;
    }

    /** Trả thuộc tính BR-018, giữ nguyên qua mọi bước chuyển trạng thái. */
    public VehicleSpecifications specifications() {
        return specifications;
    }
}
