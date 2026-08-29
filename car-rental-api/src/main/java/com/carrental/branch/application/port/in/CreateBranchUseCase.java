package com.carrental.branch.application.port.in;

import com.carrental.branch.application.command.CreateBranchCommand;
import com.carrental.branch.application.view.BranchDetail;

/**
 * Cổng đầu vào cho chức năng tạo chi nhánh.
 *
 * <p>Người gọi phụ thuộc hợp đồng này thay vì lớp service cụ thể
 * hoặc cách lưu dữ liệu.
 *
 * <p>Vị trí chi nhánh phục vụ BR-003. Mã nghiệp vụ được sinh
 * tại application theo database-guideline mục 2.
 */
public interface CreateBranchUseCase {

    /**
     * Tạo chi nhánh từ dữ liệu đầu vào đã được kiểm tra.
     *
     * <p>Phần hiện thực chịu trách nhiệm sinh mã nghiệp vụ,
     * lưu chi nhánh và đọc lại dữ liệu đã lưu trước khi trả kết quả.
     *
     * @param command yêu cầu tạo chi nhánh đã được kiểm tra, không được null
     * @return thông tin chi tiết của chi nhánh đã được lưu
     */
    BranchDetail create(CreateBranchCommand command);
}