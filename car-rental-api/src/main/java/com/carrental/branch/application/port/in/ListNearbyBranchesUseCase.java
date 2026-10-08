package com.carrental.branch.application.port.in;

import com.carrental.branch.application.query.ListNearbyBranchesQuery;
import com.carrental.branch.application.view.BranchDistanceSummary;
import java.util.List;

/** Cổng đọc các chi nhánh trong bán kính theo BR-003, BR-808 và ADR-0007. */
public interface ListNearbyBranchesUseCase {
    /** Trả toàn bộ ứng viên theo khoảng cách rồi ID, chưa phân trang hoặc truy vấn xe. */
    List<BranchDistanceSummary> list(ListNearbyBranchesQuery query);
}
