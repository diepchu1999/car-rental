package com.carrental.branch.application.service;

import com.carrental.branch.application.port.in.FindBranchUseCase;
import com.carrental.branch.application.port.in.GetBranchUseCase;
import com.carrental.branch.application.port.in.ListNearbyBranchesUseCase;
import com.carrental.branch.application.port.out.ReadBranchPort;
import com.carrental.branch.application.query.GetBranchQuery;
import com.carrental.branch.application.query.ListNearbyBranchesQuery;
import com.carrental.branch.application.view.BranchDetail;
import com.carrental.branch.application.view.BranchDistanceSummary;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.shared.validation.Validations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;

/**
 * Điều phối tra cứu chi nhánh theo mã hoặc bán kính (BR-003, BR-808).
 * Service trả read model nội bộ; adapter/in/internal ánh xạ hợp đồng cross-module.
 * Không ghi dữ liệu, không tính khoảng cách hoặc đọc dữ liệu module vehicle.
 */
@Service
class BranchQueryService implements GetBranchUseCase, FindBranchUseCase, ListNearbyBranchesUseCase {
    private final ReadBranchPort readBranchPort;

    /** Nhận một cổng đọc gộp cho resource chi nhánh theo module-architecture §3. */
    BranchQueryService(ReadBranchPort readBranchPort) {
        this.readBranchPort = readBranchPort;
    }

    /** Đọc bắt buộc cho API admin; giữ nguyên BRANCH_NOT_FOUND khi không có chi nhánh. */
    @Override
    @Transactional(readOnly = true)
    public BranchDetail get(GetBranchQuery query) {
        Validations.required(query, "query");
        return readBranchPort.findByCode(query.code())
                .orElseThrow(() -> DomainException.notFound(ErrorCode.BRANCH_NOT_FOUND));
    }

    /** Đọc tùy chọn cho cổng cross-module: không tìm thấy trả rỗng, lỗi lưu trữ truyền nguyên. */
    @Override
    @Transactional(readOnly = true)
    public Optional<BranchDetail> find(GetBranchQuery query) {
        Validations.required(query, "query");
        return readBranchPort.findByCode(query.code());
    }

    /** Trả toàn bộ chi nhánh trong bán kính bằng một lượt đọc, chưa phân trang tìm xe. */
    @Override
    @Transactional(readOnly = true)
    public List<BranchDistanceSummary> list(ListNearbyBranchesQuery query) {
        Validations.required(query, "query");
        return List.copyOf(readBranchPort.findWithinRadius(query));
    }
}
