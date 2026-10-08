package com.carrental.branch.application.port.in;

import com.carrental.branch.application.query.FindBranchByIdQuery;
import com.carrental.branch.application.view.BranchDetail;
import java.util.Optional;

/** Cổng đọc theo ID cho adapter cross-module, không công bố endpoint theo khóa chính. */
public interface FindBranchByIdUseCase {
    /** Trả view nếu tham chiếu tồn tại; lỗi lưu trữ phải truyền nguyên trạng. */
    Optional<BranchDetail> findById(FindBranchByIdQuery query);
}
