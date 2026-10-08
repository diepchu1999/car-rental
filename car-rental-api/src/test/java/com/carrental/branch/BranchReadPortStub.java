package com.carrental.branch;

import com.carrental.branch.application.port.out.ReadBranchPort;
import com.carrental.branch.application.query.ListNearbyBranchesQuery;
import com.carrental.branch.application.view.BranchDetail;
import com.carrental.branch.application.view.BranchDistanceSummary;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** Stub dùng cho các test cũ chỉ tra theo mã; gọi nhầm truy vấn bán kính phải làm test đỏ. */
public final class BranchReadPortStub implements ReadBranchPort {
    private final Function<String, Optional<BranchDetail>> lookup;

    /** Nhận hành vi tra cứu do từng kịch bản quyết định. */
    private BranchReadPortStub(Function<String, Optional<BranchDetail>> lookup) {
        this.lookup = lookup;
    }

    /** Bọc lambda cũ mà không thêm default method giả vào port production. */
    public static ReadBranchPort byCode(Function<String, Optional<BranchDetail>> lookup) {
        return new BranchReadPortStub(lookup);
    }

    /** Chuyển nguyên mã sang hành vi của test. */
    @Override
    public Optional<BranchDetail> findByCode(String code) { return lookup.apply(code); }

    /** Stub luồng tra mã phải báo lỗi nếu vô tình bị dùng cho luồng tra ID. */
    @Override
    public Optional<BranchDetail> findById(long id) {
        throw new AssertionError("ID lookup is not expected in this scenario.");
    }

    /** Không cho test luồng tra theo mã vô tình xanh khi service gọi thêm truy vấn địa lý. */
    @Override
    public List<BranchDistanceSummary> findWithinRadius(ListNearbyBranchesQuery query) {
        throw new AssertionError("Radius lookup is not expected in this scenario.");
    }
}
