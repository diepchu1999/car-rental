package com.carrental.search.domain;

/**
 * Khóa cursor: PRICE_ASC dùng tiền thuê trước khoảng cách/mã; NEAREST chỉ khoảng cách/mã.
 * Không dùng ID nội bộ, loại sở hữu hoặc mã chi nhánh để xếp hạng (BR-112/126).
 */
public record SearchPosition(SearchSort sort, long rentalAmount, double distanceMeters,
        String vehicleCode) implements Comparable<SearchPosition> {
    /** Không chấp nhận khóa sắp xếp vô nghĩa từ cursor hoặc dữ liệu nội bộ. */
    public SearchPosition {
        if (sort == null || sort == SearchSort.RATING_DESC
                || (sort == SearchSort.NEAREST && rentalAmount != 0)
                || (sort == SearchSort.PRICE_ASC && (rentalAmount <= 0 || rentalAmount % 1_000 != 0))
                || !Double.isFinite(distanceMeters) || distanceMeters < 0
                || vehicleCode == null || !vehicleCode.matches("XE-[A-Z0-9]{6}")) {
            throw new IllegalArgumentException("Invalid search cursor position.");
        }
        distanceMeters = distanceMeters == 0 ? 0 : distanceMeters;
    }

    /** Khóa NEAREST không có tiền thuê trong thứ tự, nên dùng giá trị sentinel 0 trong cursor. */
    public SearchPosition(double distanceMeters, String vehicleCode) {
        this(SearchSort.NEAREST, 0, distanceMeters, vehicleCode);
    }

    /** So sánh từ điển, giúp các xe cùng chi nhánh vẫn có thứ tự toàn phần ổn định. */
    @Override
    public int compareTo(SearchPosition other) {
        if (sort != other.sort) throw new IllegalArgumentException("Cannot compare different search sorts.");
        int priceOrder = Long.compare(rentalAmount, other.rentalAmount);
        if (priceOrder != 0) return priceOrder;
        int distanceOrder = Double.compare(distanceMeters, other.distanceMeters);
        return distanceOrder != 0 ? distanceOrder : vehicleCode.compareTo(other.vehicleCode);
    }
}
