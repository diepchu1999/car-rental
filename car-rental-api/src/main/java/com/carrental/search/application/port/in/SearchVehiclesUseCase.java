package com.carrental.search.application.port.in;

import com.carrental.search.application.query.SearchVehiclesQuery;
import com.carrental.search.application.view.SearchVehiclesPage;

/** Cổng tìm xe theo vị trí và thời gian BR-125; chỉ đọc, không hứa giữ được xe. */
public interface SearchVehiclesUseCase {
    /** Kiểm điều kiện, lọc xe bận và phân trang; lỗi đầu vào/nghiệp vụ được truyền ra thay vì trả rỗng. */
    SearchVehiclesPage search(SearchVehiclesQuery query);
}
