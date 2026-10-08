package com.carrental.branch.application.service;

import com.carrental.branch.application.command.CreateBranchCommand;
import com.carrental.branch.application.port.in.CreateBranchUseCase;
import com.carrental.branch.application.port.out.ReadBranchPort;
import com.carrental.branch.application.port.out.WriteBranchPort;
import com.carrental.branch.application.view.BranchDetail;
import com.carrental.branch.domain.Branch;
import com.carrental.shared.code.BusinessCodeGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Điều phối việc tạo danh tính và vị trí chi nhánh trong phạm vi Task 4.
 *
 * <p>Vị trí chi nhánh phục vụ BR-003. Mã nghiệp vụ được sinh
 * tại application và được cơ sở dữ liệu bảo vệ tính duy nhất
 * theo database-guideline mục 2.
 *
 * <p>Việc ghi và đọc lại kết quả nằm trong cùng transaction
 * theo module-architecture mục 10.
 */
@Service
class BranchCommandService implements CreateBranchUseCase {

    private static final String CODE_PREFIX = "CN";

    /**
     * Giới hạn kỹ thuật: tối đa năm lần thử, tính cả lần đầu tiên.
     *
     * <p>Ngăn vòng lặp vô hạn nếu liên tục sinh mã đã tồn tại.
     * Đây không phải giới hạn nghiệp vụ về số lượng chi nhánh.
     */
    private static final int MAX_CODE_GENERATION_ATTEMPTS = 5;

    private final WriteBranchPort writeBranchPort;

    private final ReadBranchPort readBranchPort;

    private final BusinessCodeGenerator businessCodeGenerator;

    /**
     * Nhận các cổng lưu trữ và bộ sinh mã do Spring cung cấp.
     *
     * <p>Service chỉ biết hợp đồng của các port,
     * không phụ thuộc persistence adapter hoặc JDBC.
     *
     * @param writeBranchPort cổng chèn chi nhánh
     * @param readBranchPort cổng đọc lại chi nhánh đã lưu
     * @param businessCodeGenerator bộ sinh mã nghiệp vụ
     */
    BranchCommandService(
            WriteBranchPort writeBranchPort,
            ReadBranchPort readBranchPort,
            BusinessCodeGenerator businessCodeGenerator
    ) {
        this.writeBranchPort = writeBranchPort;
        this.readBranchPort = readBranchPort;
        this.businessCodeGenerator = businessCodeGenerator;
    }

    /**
     * Sinh mã, chèn chi nhánh và trả dữ liệu đọc lại từ cơ sở dữ liệu.
     *
     * <p>Chỉ thử mã khác khi cổng ghi trả false do trùng mã nghiệp vụ.
     * Các lỗi lưu trữ khác được truyền ra ngoài, không bị coi là trùng mã.
     *
     * <p>Không kiểm tra mã tồn tại trước khi chèn.
     * Ràng buộc UNIQUE trong cơ sở dữ liệu quyết định việc chèn có hợp lệ.
     *
     * <p>Nếu chèn thành công nhưng không đọc lại được,
     * đây là lỗi nội bộ và transaction phải rollback.
     *
     * @param command yêu cầu tạo chi nhánh đã được kiểm tra, không được null
     * @return thông tin chi nhánh được đọc lại sau khi chèn thành công
     * @throws IllegalStateException nếu hết lượt thử mã
     *                               hoặc không đọc lại được chi nhánh vừa chèn
     */
    @Override
    @Transactional
    public BranchDetail create(CreateBranchCommand command) {
        for (int attempt = 0;
             attempt < MAX_CODE_GENERATION_ATTEMPTS;
             attempt++) {

            String code = businessCodeGenerator.generate(CODE_PREFIX);

            Branch branch = new Branch(
                    code,
                    command.location(),
                    command.name(),
                    command.address()
            );

            if (!writeBranchPort.insert(branch)) {
                continue;
            }

            return readBranchPort.findByCode(code)
                    .orElseThrow(() -> new IllegalStateException(
                            "Created branch could not be reloaded: " + code
                    ));
        }

        throw new IllegalStateException(
                "Failed to create a branch with a unique code after "
                        + MAX_CODE_GENERATION_ATTEMPTS
                        + " attempts."
        );
    }
}
