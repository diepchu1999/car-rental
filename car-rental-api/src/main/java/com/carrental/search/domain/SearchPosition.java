package com.carrental.search.domain;

/**
 * Khóa thứ tự cursor theo api-guideline §8: khoảng cách mét chưa làm tròn rồi ID xe.
 * ID chỉ là khóa phụ kỹ thuật, không dùng loại sở hữu hoặc mã chi nhánh để xếp hạng (BR-112).
 */
public record SearchPosition(double distanceMeters, long vehicleId) implements Comparable<SearchPosition> {
    /** Không chấp nhận khóa sắp xếp vô nghĩa từ cursor hoặc dữ liệu nội bộ. */
    public SearchPosition {
        if (!Double.isFinite(distanceMeters) || distanceMeters < 0 || vehicleId <= 0) {
            throw new IllegalArgumentException("Invalid search cursor position.");
        }
        distanceMeters = distanceMeters == 0 ? 0 : distanceMeters;
    }

    /** So sánh từ điển, giúp các xe cùng chi nhánh vẫn có thứ tự toàn phần ổn định. */
    @Override
    public int compareTo(SearchPosition other) {
        int distanceOrder = Double.compare(distanceMeters, other.distanceMeters);
        return distanceOrder != 0 ? distanceOrder : Long.compare(vehicleId, other.vehicleId);
    }
}
