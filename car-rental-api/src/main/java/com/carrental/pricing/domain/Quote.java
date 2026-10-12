package com.carrental.pricing.domain;

import com.carrental.shared.rental.DriveMode;
import com.carrental.shared.rental.PickupMethod;
import com.carrental.shared.rental.RentalType;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Báo giá bất biến theo BR-122; không thu tiền, không giữ chỗ và không đọc lại cấu hình.
 * @param code mã BG-yymm-6 do application sinh
 * @param vehicleCode mã xe bất biến dùng đối chiếu/hiển thị theo ADR-0008
 * @param startAt giờ nhận thực tế
 * @param endAt giờ trả thực tế
 * @param rentalType gói đã được resolver kiểm hỗ trợ
 * @param driveMode hình thức lái đã kiểm hỗ trợ
 * @param pickupMethod cách nhận đã kiểm hỗ trợ
 * @param snapshot tham số đã dùng, không chứa lịch lễ
 * @param price các dòng và tổng đã tính
 * @param createdAt mốc tạo chụp một lần từ Clock của application
 * @param expiresAt hạn tính từ mốc tạo và giá trị trong snapshot
 */
public record Quote(String code, String vehicleCode, Instant startAt, Instant endAt,
        RentalType rentalType, DriveMode driveMode, PickupMethod pickupMethod,
        QuoteSnapshot snapshot, CalculatedPrice price, Instant createdAt, Instant expiresAt) {
    private static final Pattern CODE = Pattern.compile("BG-[0-9]{2}(0[1-9]|1[0-2])-[A-Z0-9]{6}");
    private static final Pattern VEHICLE_CODE = Pattern.compile("XE-[A-Z0-9]{6}");

    /** Không cho một báo giá mang tổng/giá/hạn khác với dữ liệu đã tính ở lúc tạo. */
    public Quote {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(vehicleCode, "vehicleCode");
        Objects.requireNonNull(startAt, "startAt");
        Objects.requireNonNull(endAt, "endAt");
        Objects.requireNonNull(rentalType, "rentalType");
        Objects.requireNonNull(driveMode, "driveMode");
        Objects.requireNonNull(pickupMethod, "pickupMethod");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(price, "price");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!CODE.matcher(code).matches() || !VEHICLE_CODE.matcher(vehicleCode).matches()
                || !endAt.isAfter(startAt) || createdAt.getNano() % 1_000 != 0
                || startAt.getNano() % 1_000 != 0 || endAt.getNano() % 1_000 != 0
                || !expiresAt.equals(createdAt.plus(snapshot.quoteValidity()))
                || price.lines().stream().anyMatch(line -> line.dailyRate() != snapshot.dailyRate())) {
            throw new IllegalArgumentException("Invalid quote values.");
        }
    }

    /** Đóng băng hạn tại lúc tạo; mọi lượt thử mã giữ nguyên các giá trị còn lại (BR-122). */
    public static Quote create(String code, String vehicleCode, Instant startAt, Instant endAt,
            RentalType rentalType, DriveMode driveMode, PickupMethod pickupMethod,
            QuoteSnapshot snapshot, CalculatedPrice price, Instant createdAt) {
        return new Quote(code, vehicleCode, startAt, endAt, rentalType, driveMode, pickupMethod,
                snapshot, price, createdAt, createdAt.plus(snapshot.quoteValidity()));
    }
}
