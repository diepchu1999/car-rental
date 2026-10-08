package com.carrental.search.adapter.in.rest.publicapi.response;

import com.carrental.search.application.view.SearchVehiclesPage;
import java.util.List;

/** Trang công khai theo api-guideline §8; nextCursor null khi không còn trang kế tiếp ở lần đọc này. */
public record SearchVehiclesResponse(List<SearchVehicleResponse> items, String nextCursor) {
    /** Giữ danh sách bất biến sau khi chuyển đổi. */
    public SearchVehiclesResponse {
        items = List.copyOf(items);
    }

    /** Ánh xạ mọi xe sang response riêng và giữ nguyên cursor opaque do application tạo. */
    public static SearchVehiclesResponse fromDomain(SearchVehiclesPage page) {
        return new SearchVehiclesResponse(page.items().stream().map(SearchVehicleResponse::fromDomain).toList(),
                page.nextCursor());
    }
}
