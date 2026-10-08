package com.carrental.branch.application.port.in;

import com.carrental.branch.application.query.GetBranchQuery;
import com.carrental.branch.application.view.BranchDetail;
import java.util.Optional;

/** Tra chi nhánh tùy chọn để cổng cross-module giữ nguyên hợp đồng không tìm thấy (BR-003). */
public interface FindBranchUseCase {
    /** Trả view nội bộ nếu tồn tại; lỗi lưu trữ phải truyền ra, không đổi thành rỗng. */
    Optional<BranchDetail> find(GetBranchQuery query);
}
