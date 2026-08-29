package com.carrental.branch.application.port.in;

import com.carrental.branch.application.query.GetBranchQuery;
import com.carrental.branch.application.view.BranchDetail;
import com.carrental.shared.error.DomainException;

/**
 * Cổng đầu vào cho chức năng đọc chi tiết chi nhánh theo mã nghiệp vụ.
 *
 * <p>Người gọi không cần biết cách tra cứu hoặc nơi lưu dữ liệu.
 * Chức năng này chỉ đọc, không thay đổi chi nhánh.
 */
public interface GetBranchUseCase {

    /**
     * Lấy thông tin chi tiết của chi nhánh có mã được yêu cầu.
     *
     * @param query yêu cầu tra cứu đã được kiểm tra, không được null
     * @return thông tin chi tiết của chi nhánh tìm được
     * @throws DomainException nếu không tìm thấy chi nhánh,
     *                        với mã lỗi {@code BRANCH_NOT_FOUND}
     */
    BranchDetail get(GetBranchQuery query);
}