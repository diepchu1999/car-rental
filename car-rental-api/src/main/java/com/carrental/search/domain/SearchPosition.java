package com.carrental.search.domain;

/**
 * Khóa thứ tự cursor: khoảng cách mét chưa làm tròn rồi mã xe theo database-guideline §2.
 * Không dùng ID nội bộ, loại sở hữu hoặc mã chi nhánh để xếp hạng (BR-112/126).
 */
public record SearchPosition(double distanceMeters, String vehicleCode) implements Comparable<SearchPosition> {
    /** Không chấp nhận khóa sắp xếp vô nghĩa từ cursor hoặc dữ liệu nội bộ. */
    public SearchPosition {
        if (!Double.isFinite(distanceMeters) || distanceMeters < 0
                || vehicleCode == null || !vehicleCode.matches("XE-[A-Z0-9]{6}")) {
            throw new IllegalArgumentException("Invalid search cursor position.");
        }
        distanceMeters = distanceMeters == 0 ? 0 : distanceMeters;
    }

    /** So sánh từ điển, giúp các xe cùng chi nhánh vẫn có thứ tự toàn phần ổn định. */
    @Override
    public int compareTo(SearchPosition other) {
        int distanceOrder = Double.compare(distanceMeters, other.distanceMeters);
        return distanceOrder != 0 ? distanceOrder : vehicleCode.compareTo(other.vehicleCode);
    }
}
