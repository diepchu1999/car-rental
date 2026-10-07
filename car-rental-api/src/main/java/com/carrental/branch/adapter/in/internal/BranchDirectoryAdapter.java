package com.carrental.branch.adapter.in.internal;

import com.carrental.branch.api.BranchDirectory;
import com.carrental.branch.api.BranchRef;
import com.carrental.branch.api.BranchSearchView;
import com.carrental.branch.application.port.in.FindBranchUseCase;
import com.carrental.branch.application.port.in.ListNearbyBranchesUseCase;
import com.carrental.branch.application.query.GetBranchQuery;
import com.carrental.branch.application.query.ListNearbyBranchesQuery;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Optional;

/** Nối cổng cross-module với use case và đổi view nội bộ sang API theo ADR-0004/0008. */
@Component
class BranchDirectoryAdapter implements BranchDirectory {
    private final FindBranchUseCase lookup;
    private final ListNearbyBranchesUseCase nearby;

    /** Nhận các use case được Spring bọc transaction; không truy cập persistence trực tiếp. */
    BranchDirectoryAdapter(FindBranchUseCase lookup, ListNearbyBranchesUseCase nearby) {
        this.lookup = lookup;
        this.nearby = nearby;
    }

    /** Giữ nguyên mã đầu vào, kết quả rỗng và lỗi của hợp đồng tra chi nhánh cũ (BR-003). */
    @Override
    public Optional<BranchRef> findByCode(String code) {
        return lookup.find(GetBranchQuery.from(code))
                .map(detail -> new BranchRef(detail.id(), detail.code()));
    }

    /** Chỉ ánh xạ dữ liệu; không đổi mét sang km, không tính lại khoảng cách. */
    @Override
    public List<BranchSearchView> findWithinRadius(Double latitude, Double longitude, Double radiusMeters) {
        return nearby.list(ListNearbyBranchesQuery.from(latitude, longitude, radiusMeters)).stream()
                .map(view -> new BranchSearchView(view.id(), view.code(), view.name(),
                        view.address(), view.distanceMeters()))
                .toList();
    }
}
