package com.carrental.branch.application.service;

import com.carrental.branch.application.port.in.FindBranchUseCase;
import com.carrental.branch.application.port.in.FindBranchByIdUseCase;
import com.carrental.branch.application.query.FindBranchByIdQuery;
import com.carrental.branch.application.port.in.GetBranchUseCase;
import com.carrental.branch.application.port.in.ListNearbyBranchesUseCase;
import com.carrental.branch.application.port.out.ReadBranchPort;
import com.carrental.branch.application.query.GetBranchQuery;
import com.carrental.branch.application.query.ListNearbyBranchesQuery;
import com.carrental.branch.application.view.BranchDetail;
import com.carrental.branch.application.view.BranchDistanceSummary;
import com.carrental.identity.api.CallerDirectory;
import com.carrental.identity.api.CallerRef;
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
class BranchQueryService implements GetBranchUseCase, FindBranchUseCase, FindBranchByIdUseCase, ListNearbyBranchesUseCase {
    private final ReadBranchPort readBranchPort;
    private final CallerDirectory callers;

    /** Nhận cổng đọc và danh tính; chỉ luồng get cho quản trị dùng CallerDirectory (BR-810). */
    BranchQueryService(ReadBranchPort readBranchPort, CallerDirectory callers) {
        this.readBranchPort = readBranchPort;
        this.callers = callers;
    }

    /**
     * BR-810: ADMIN đọc mọi chi nhánh; STAFF/MANAGER chỉ đọc chi nhánh được gán.
     * Kiểm vai và việc gán trước truy vấn; so ID nội bộ trước khi trả view.
     * Người có quyền đọc nhưng mã không tồn tại vẫn nhận BRANCH_NOT_FOUND.
     * Các luồng tra cứu nội bộ bên dưới không áp quyền của endpoint quản trị.
     */
    @Override
    @Transactional(readOnly = true)
    public BranchDetail get(GetBranchQuery query) {
        Validations.required(query, "query");
        CallerRef caller = callers.current();
        boolean admin = caller.hasRole("ADMIN");
        if (!admin) {
            if (!caller.hasRole("BRANCH_STAFF") && !caller.hasRole("BRANCH_MANAGER")) {
                throw DomainException.forbidden(ErrorCode.ACCESS_DENIED);
            }
            if (caller.branchId() == null) {
                throw DomainException.forbidden(ErrorCode.STAFF_BRANCH_REQUIRED);
            }
        }
        BranchDetail branch = readBranchPort.findByCode(query.code())
                .orElseThrow(() -> DomainException.notFound(ErrorCode.BRANCH_NOT_FOUND));
        if (!admin && caller.branchId().longValue() != branch.id()) {
            throw DomainException.forbidden(ErrorCode.ACCESS_DENIED);
        }
        return branch;
    }

    /** Đọc tùy chọn cho cổng cross-module: không tìm thấy trả rỗng, lỗi lưu trữ truyền nguyên. */
    @Override
    @Transactional(readOnly = true)
    public Optional<BranchDetail> find(GetBranchQuery query) {
        Validations.required(query, "query");
        return readBranchPort.findByCode(query.code());
    }

    /** Tra tham chiếu nội bộ trong transaction chỉ đọc theo ADR-0008; không tự đổi thành lỗi 404. */
    @Override
    @Transactional(readOnly = true)
    public Optional<BranchDetail> findById(FindBranchByIdQuery query) {
        Validations.required(query, "query");
        return readBranchPort.findById(query.id());
    }

    /** Trả toàn bộ chi nhánh trong bán kính bằng một lượt đọc, chưa phân trang tìm xe. */
    @Override
    @Transactional(readOnly = true)
    public List<BranchDistanceSummary> list(ListNearbyBranchesQuery query) {
        Validations.required(query, "query");
        return List.copyOf(readBranchPort.findWithinRadius(query));
    }
}
