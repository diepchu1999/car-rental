package com.carrental.search.application.service;

import com.carrental.availability.api.AvailabilityDirectory;
import com.carrental.availability.api.Period;
import com.carrental.booking.api.RentalTermsDirectory;
import com.carrental.branch.api.BranchDirectory;
import com.carrental.branch.api.BranchSearchView;
import com.carrental.search.application.port.in.SearchVehiclesUseCase;
import com.carrental.search.application.query.SearchVehiclesQuery;
import com.carrental.search.application.view.SearchBranchSummary;
import com.carrental.search.application.view.SearchVehicleListItem;
import com.carrental.search.application.view.SearchVehiclesPage;
import com.carrental.search.domain.SearchPosition;
import com.carrental.search.domain.SearchRadiusSettings;
import com.carrental.shared.validation.Validations;
import com.carrental.vehicle.api.VehicleSearchDirectory;
import com.carrental.vehicle.api.VehicleSearchView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Điều phối BR-125 qua các API module, không SQL riêng và không biết loại sở hữu (BR-112).
 * Lát cắt 1 lấy toàn bộ ứng viên rồi phân trang trong bộ nhớ theo kế hoạch đã duyệt.
 * Kết quả không phải giữ chỗ: BR-104 vẫn do exclusion constraint quyết định ở thao tác hold.
 */
@Service("searchVehicleQueryService")
class VehicleSearchQueryService implements SearchVehiclesUseCase {
    private final RentalTermsDirectory terms;
    private final BranchDirectory branches;
    private final VehicleSearchDirectory vehicles;
    private final AvailabilityDirectory availability;
    private final SearchRadiusSettings radii;
    private final SearchSupportPolicyResolver support;

    /** Chỉ nhận cổng công khai của module sở hữu và chính sách của search; không import tầng nội bộ. */
    VehicleSearchQueryService(RentalTermsDirectory terms, BranchDirectory branches,
            VehicleSearchDirectory vehicles, AvailabilityDirectory availability,
            SearchRadiusSettings radii, SearchSupportPolicyResolver support) {
        this.terms = terms;
        this.branches = branches;
        this.vehicles = vehicles;
        this.availability = availability;
        this.radii = radii;
        this.support = support;
    }

    /**
     * Kiểm hỗ trợ và điều kiện thuê trước khi tìm ứng viên; gọi mỗi directory dữ liệu tối đa một lần.
     * Truyền khoảng thuê thực tế và buffer riêng: chỉ availability cộng đệm (BR-109/116).
     * Lọc xe bận trước phân trang để không trả trang thiếu khi phía sau vẫn có xe phù hợp.
     */
    @Override
    @Transactional(readOnly = true)
    public SearchVehiclesPage search(SearchVehiclesQuery query) {
        Validations.required(query, "query");
        support.resolve(query.rentalType(), query.driveMode(), query.pickupMethod(), query.sort()).validate();
        double radiusKm = radii.resolve(query.radiusKm());
        byte[] fingerprint = SearchCursorCodec.fingerprint(query, radiusKm);
        var cursor = SearchCursorCodec.decode(query.cursor(), fingerprint);
        var rentalTerms = terms.getTerms(query.rentalType(), query.pickupMethod(),
                query.startInclusive(), query.endExclusive());
        var nearby = branches.findWithinRadius(query.latitude(), query.longitude(), radiusKm * 1000);
        if (nearby.isEmpty()) {
            return new SearchVehiclesPage(List.of(), null);
        }
        var branchesById = nearby.stream().collect(Collectors.toMap(BranchSearchView::id, branch -> branch));
        var filter = query.filters();
        var candidates = vehicles.list(nearby.stream().map(BranchSearchView::id).toList(),
                query.rentalType(), filter.seats(), filter.transmission(), filter.fuelType(),
                filter.make(), filter.model(), filter.collateralFree());
        if (candidates.isEmpty()) {
            return new SearchVehiclesPage(List.of(), null);
        }
        var busy = availability.findBusyVehicleIds(new Period(query.startInclusive(), query.endExclusive()),
                rentalTerms.turnaroundBuffer(), candidates.stream().map(VehicleSearchView::id).toList());
        var afterCursor = candidates.stream()
                .filter(vehicle -> !busy.contains(vehicle.id()))
                .map(vehicle -> toListItem(vehicle, branchesById.get(vehicle.branchId())))
                .sorted(Comparator.comparing(VehicleSearchQueryService::position))
                .filter(item -> cursor.isEmpty() || position(item).compareTo(cursor.orElseThrow()) > 0)
                .limit(query.limit() + 1L)
                .toList();
        boolean hasNext = afterCursor.size() > query.limit();
        var items = hasNext ? afterCursor.subList(0, query.limit()) : afterCursor;
        String nextCursor = hasNext
                ? SearchCursorCodec.encode(position(items.getLast()), fingerprint) : null;
        return new SearchVehiclesPage(items, nextCursor);
    }

    /** Ghép đúng chi nhánh theo ID, giữ khoảng cách và cờ thế chấp do module sở hữu trả về. */
    private SearchVehicleListItem toListItem(VehicleSearchView vehicle, BranchSearchView branch) {
        if (branch == null) {
            throw new IllegalStateException("Vehicle search returned a vehicle outside the requested branches.");
        }
        return new SearchVehicleListItem(vehicle.id(), vehicle.code(), vehicle.make(), vehicle.model(),
                vehicle.seats(), vehicle.transmission(), vehicle.fuelType(), vehicle.collateralFree(),
                new SearchBranchSummary(branch.code(), branch.name(), branch.address()), branch.distanceMeters());
    }

    /** Dùng chung khóa so sánh và khóa ghi cursor để không lệch thứ tự giữa hai trang. */
    private static SearchPosition position(SearchVehicleListItem item) {
        return new SearchPosition(item.distanceMeters(), item.code());
    }
}
