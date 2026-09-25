package com.carrental.availability.domain;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.validation.Validations;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;

/**
 * Biểu diễn khoảng thời gian khóa lịch với cận dưới đóng,
 * cận trên mở theo ADR-0005 và database-guideline §4.
 *
 * <p>Khoảng hữu hạn có dạng [startInclusive, endExclusive).
 * Thời điểm bắt đầu thuộc khoảng; thời điểm kết thúc không thuộc khoảng.
 *
 * <p>endExclusive bằng null biểu diễn khoảng không chặn trên
 * theo BR-015. Aggregate Reservation chịu trách nhiệm bảo đảm
 * chỉ COMPLIANCE_HOLD được sử dụng dạng khoảng này.
 *
 * <p>Đối tượng bất biến, không đọc đồng hồ hệ thống, không biết
 * loại gói thuê và không quyết định xe có còn trống hay không.
 * PostgreSQL là nơi bảo đảm chống chồng lịch theo BR-104.
 *
 * @param startInclusive thời điểm bắt đầu, bắt buộc có giá trị
 * @param endExclusive thời điểm kết thúc không thuộc khoảng;
 *                     null nếu không chặn trên
 */
public record ReservationPeriod(
        Instant startInclusive,
        Instant endExclusive
) {

    /**
     * Kiểm tra hình dạng khoảng ngay khi khởi tạo.
     *
     * <p>Khoảng hữu hạn phải có thời điểm kết thúc sau thời điểm
     * bắt đầu. Không chấp nhận khoảng rỗng hoặc đảo ngược.
     *
     * <p>Không kiểm khoảng nằm trong quá khứ hay tương lai:
     * cùng kiểu dữ liệu này còn được dùng để khôi phục hồ sơ đã lưu.
     *
     * @param startInclusive thời điểm bắt đầu
     * @param endExclusive thời điểm kết thúc hoặc null
     * @throws DomainException nếu thiếu thời điểm bắt đầu
     *                         hoặc khoảng hữu hạn không hợp lệ
     */
    public ReservationPeriod {
        startInclusive = Validations.required(
                startInclusive,
                "startInclusive"
        );

        if (endExclusive != null
                && !endExclusive.isAfter(startInclusive)) {
            throw DomainException.invalidInput(
                    "endExclusive must be after startInclusive."
            );
        }
    }

    /**
     * Tạo khoảng hữu hạn với cận dưới đóng và cận trên mở.
     *
     * <p>Phương thức này yêu cầu cả hai đầu mút. Không diễn giải
     * việc thiếu thời điểm kết thúc thành một khoảng vô hạn.
     *
     * @param startInclusive thời điểm bắt đầu
     * @param endExclusive thời điểm kết thúc
     * @return khoảng hữu hạn hợp lệ
     * @throws DomainException nếu thiếu đầu mút
     *                         hoặc thời điểm kết thúc không sau bắt đầu
     */
    public static ReservationPeriod finite(
            Instant startInclusive,
            Instant endExclusive
    ) {
        return new ReservationPeriod(
                startInclusive,
                Validations.required(endExclusive, "endExclusive")
        );
    }

    /**
     * Tạo khoảng bắt đầu tại một thời điểm và không chặn trên.
     *
     * <p>Dùng để biểu diễn khóa do giấy tờ hết hạn theo BR-015.
     * Không dùng ngày giả hoặc Instant.MAX thay cho cận trên bị thiếu.
     *
     * @param startInclusive thời điểm bắt đầu chặn
     * @return khoảng không chặn trên
     * @throws DomainException nếu thiếu thời điểm bắt đầu
     */
    public static ReservationPeriod unboundedFrom(
            Instant startInclusive
    ) {
        return new ReservationPeriod(startInclusive, null);
    }

    /**
     * Cho biết khoảng có thiếu cận trên hay không.
     *
     * @return true nếu khoảng không chặn trên
     */
    public boolean isUnbounded() {
        return endExclusive == null;
    }

    /**
     * Tạo khoảng mới bằng cách cộng đệm vào cuối khoảng hữu hạn.
     *
     * <p>BR-109 và BR-116 yêu cầu khoảng đệm nằm bên trong
     * khoảng khóa lịch. Bên gọi cung cấp độ dài đã được phân giải;
     * phương thức không biết RentalType và không tự chọn độ dài.
     *
     * <p>Giữ nguyên thời điểm bắt đầu. Đệm bằng không hợp lệ;
     * đệm âm không hợp lệ. Không sửa đối tượng hiện tại.
     *
     * <p>Không áp dụng phép cộng đệm cho khoảng không chặn trên.
     *
     * @param buffer độ dài đệm cần cộng, không âm
     * @return khoảng hữu hạn mới đã cộng đệm
     * @throws DomainException nếu đệm thiếu hoặc âm,
     *                         khoảng không chặn trên,
     *                         hoặc kết quả vượt giới hạn Instant
     */
    public ReservationPeriod withBuffer(Duration buffer) {
        Duration checkedBuffer = Validations.required(
                buffer,
                "buffer"
        );

        if (checkedBuffer.isNegative()) {
            throw DomainException.invalidInput(
                    "buffer must not be negative."
            );
        }

        if (isUnbounded()) {
            throw DomainException.invalidInput(
                    "A finite period is required to apply a buffer."
            );
        }

        Instant bufferedEnd;
        try {
            bufferedEnd = endExclusive.plus(checkedBuffer);
        } catch (DateTimeException | ArithmeticException exception) {
            throw DomainException.invalidInput(
                    "Buffered period exceeds the supported time range."
            );
        }

        return new ReservationPeriod(startInclusive, bufferedEnd);
    }
}