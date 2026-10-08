package com.carrental.search;

import com.carrental.search.application.query.SearchVehiclesQuery;
import com.carrental.search.domain.SearchSort;
import com.carrental.search.domain.VehicleSearchFilters;
import com.carrental.shared.rental.DriveMode;
import com.carrental.shared.rental.PickupMethod;
import com.carrental.shared.rental.RentalType;
import java.time.Instant;

/** Giá trị đầu vào chung cho test search; không được đóng gói vào production. */
public final class SearchTestQueries {
    /** Ngăn tạo lớp tiện ích. */
    private SearchTestQueries() {
    }

    /** Tạo bộ đầu vào mới cho từng test, không chia sẻ trạng thái có thể thay đổi. */
    public static Input input() {
        return new Input();
    }

    /** Builder chỉ ở test giúp từng kịch bản thay đúng trường cần kiểm. */
    public static final class Input {
        public Double latitude = 10.76;
        public Double longitude = 106.66;
        public Instant start = Instant.parse("2030-01-15T03:00:00Z");
        public Instant end = Instant.parse("2030-01-15T09:00:00Z");
        public RentalType rentalType = RentalType.DAILY;
        public DriveMode driveMode = DriveMode.SELF_DRIVE;
        public PickupMethod pickupMethod;
        public Double radiusKm;
        public VehicleSearchFilters filters;
        public SearchSort sort;
        public Integer limit;
        public String cursor;

        /** Chỉ tạo qua factory để mỗi test nhận một bộ dữ liệu riêng. */
        private Input() {
        }

        /** Đi qua factory thật để không bỏ qua validation hoặc mặc định. */
        public SearchVehiclesQuery build() {
            return SearchVehiclesQuery.from(latitude, longitude, start, end,
                    rentalType, driveMode, pickupMethod, radiusKm, filters, sort, limit, cursor);
        }
    }
}
