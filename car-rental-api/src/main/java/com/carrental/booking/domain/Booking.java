package com.carrental.booking.domain;

import com.carrental.shared.rental.DriveMode;
import com.carrental.shared.rental.PickupMethod;
import com.carrental.shared.rental.RentalType;
import java.time.Instant;
import java.util.Objects;

/**
 * Đơn thuê và các giá trị đã đóng băng; không tự đọc cấu hình hoặc quyết định xe còn trống (BR-102/122).
 * Application chỉ tạo sau khi giữ chỗ thành công, rồi ghi cả hai trong cùng transaction (ADR-0005).
 * @param code mã DT-yymm-6 do application sinh
 * @param customerAccountCode chủ đã xác thực
 * @param quoteCode báo giá của đúng chủ đã kiểm hạn
 * @param vehicleCode mã xe đã tra qua vehicle.api
 * @param branchCode chi nhánh nhận/trả đã chốt (BR-105)
 * @param reservationCode mã chỗ giữ ban đầu do availability cấp
 * @param startAt giờ nhận thực tế, không chứa đệm
 * @param endAt giờ trả thực tế, không chứa đệm
 * @param rentalType gói đã được policy kiểm hỗ trợ
 * @param driveMode hình thức lái đã kiểm hỗ trợ
 * @param pickupMethod cách nhận xe đã kiểm hỗ trợ
 * @param price giá đóng băng, không có phí quyết toán giả định
 * @param rentalTerms điều kiện đã áp lúc tạo
 * @param holdExpiresAt hạn thực tế đã lưu ở availability, không tự tính lại
 * @param status trạng thái đã lưu
 * @param createdAt mốc tạo đơn
 * @param statusChangedAt mốc chuyển trạng thái gần nhất
 */
public record Booking(String code, String customerAccountCode, String quoteCode, String vehicleCode,
        String branchCode, String reservationCode, Instant startAt, Instant endAt, RentalType rentalType,
        DriveMode driveMode, PickupMethod pickupMethod, BookingPriceSnapshot price, BookingRentalTerms rentalTerms,
        Instant holdExpiresAt, BookingStatus status, Instant createdAt, Instant statusChangedAt) {
    /** Bảo vệ cấu trúc khi tạo và khôi phục; đơn đã hết hạn vẫn đọc được để job xử lý. */
    public Booking {
        requireCode(code, "DT-[0-9]{2}(0[1-9]|1[0-2])-[A-Z0-9]{6}");
        requireCode(customerAccountCode, "TK-[A-Z0-9]{6}");
        requireCode(quoteCode, "BG-[0-9]{2}(0[1-9]|1[0-2])-[A-Z0-9]{6}");
        requireCode(vehicleCode, "XE-[A-Z0-9]{6}");
        requireCode(branchCode, "CN-[A-Z0-9]{6}");
        requireCode(reservationCode, "KL-[A-Z0-9]{6}");
        requireTimestamp(startAt);
        requireTimestamp(endAt);
        requireTimestamp(holdExpiresAt);
        requireTimestamp(createdAt);
        requireTimestamp(statusChangedAt);
        Objects.requireNonNull(rentalType, "rentalType");
        Objects.requireNonNull(driveMode, "driveMode");
        Objects.requireNonNull(pickupMethod, "pickupMethod");
        Objects.requireNonNull(price, "price");
        Objects.requireNonNull(rentalTerms, "rentalTerms");
        Objects.requireNonNull(status, "status");
        if (!endAt.isAfter(startAt) || !holdExpiresAt.isAfter(createdAt) || statusChangedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("Invalid booking timestamps.");
        }
    }

    /** BR-102: đơn mới luôn chờ trả cọc, giữ đúng hạn do availability trả về. */
    public static Booking create(String code, String customerAccountCode, String quoteCode, String vehicleCode,
            String branchCode, String reservationCode, Instant startAt, Instant endAt, RentalType rentalType,
            DriveMode driveMode, PickupMethod pickupMethod, BookingPriceSnapshot price,
            BookingRentalTerms rentalTerms, Instant holdExpiresAt, Instant createdAt) {
        return new Booking(code, customerAccountCode, quoteCode, vehicleCode, branchCode, reservationCode,
                startAt, endAt, rentalType, driveMode, pickupMethod, price, rentalTerms, holdExpiresAt,
                BookingStatus.PENDING_PAYMENT, createdAt, createdAt);
    }

    /** Chặn mã hỏng mà không đưa giá trị đầu vào vào thông báo lỗi. */
    private static void requireCode(String code, String pattern) {
        if (code == null || !code.matches(pattern)) throw new IllegalArgumentException("Invalid booking reference.");
    }

    /** Mốc do application hoặc CSDL cung cấp phải lưu được nguyên vẹn ở độ chính xác micro giây. */
    private static void requireTimestamp(Instant value) {
        if (value == null || value.getNano() % 1_000 != 0) {
            throw new IllegalArgumentException("Booking timestamps require microsecond precision.");
        }
    }
}
