package com.carrental.search.application.view;

import java.util.List;

/** Trang kết quả cursor theo api-guideline §8; nextCursor null nghĩa là không còn trang sau ở lần đọc này. */
public record SearchVehiclesPage(List<SearchVehicleListItem> items, String nextCursor) {
    /** Chụp danh sách bất biến để không thay đổi kết quả sau khi đã tính cursor. */
    public SearchVehiclesPage {
        items = List.copyOf(items);
    }
}
