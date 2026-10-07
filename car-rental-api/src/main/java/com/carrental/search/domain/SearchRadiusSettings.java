package com.carrental.search.domain;

import com.carrental.shared.error.DomainException;

/** Hai tham số bán kính của BR-125, nhận từ cấu hình ứng dụng thay vì đóng cứng trong use case. */
public record SearchRadiusSettings(double defaultRadiusKm, double maxRadiusKm) {
    /** Cấu hình sai phải ngăn tạo bean; bảo đảm cả phép đổi sang mét không tràn số. */
    public SearchRadiusSettings {
        if (!Double.isFinite(defaultRadiusKm) || !Double.isFinite(maxRadiusKm)
                || defaultRadiusKm <= 0 || maxRadiusKm <= 0 || defaultRadiusKm > maxRadiusKm
                || !Double.isFinite(maxRadiusKm * 1000)) {
            throw new IllegalArgumentException("Search radii must be finite, positive and default must not exceed maximum.");
        }
    }

    /** Áp mặc định khi không gửi; vượt trần báo lỗi đầu vào, không cắt bán kính âm thầm. */
    public double resolve(Double requestedKm) {
        double result = requestedKm == null ? defaultRadiusKm : requestedKm;
        if (!Double.isFinite(result) || result <= 0 || result > maxRadiusKm) {
            throw DomainException.invalidInput("radiusKm must be greater than zero and no greater than " + maxRadiusKm + ".");
        }
        return result;
    }
}
