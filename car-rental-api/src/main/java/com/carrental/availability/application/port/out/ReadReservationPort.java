package com.carrental.availability.application.port.out;

import com.carrental.availability.domain.Reservation;
import com.carrental.availability.domain.ReservationPeriod;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;

/**
 * Cổng đọc aggregate và tra cứu xe bận theo BR-103, BR-104 và status-flow §2.
 *
 * <p>Application dùng aggregate để thực hiện chuyển trạng thái, không dựng
 * aggregate từ read view. Cổng này không thay thế bảo vệ chống trùng lịch
 * của PostgreSQL và không được dùng để đọc kiểm trống trước khi giữ chỗ.
 */
public interface ReadReservationPort {

    /**
     * Tải nguyên trạng một khóa lịch bằng mã reservation duy nhất.
     *
     * <p>Không dùng bookingCode vì một đơn có thể có nhiều khóa khi gia hạn
     * theo BR-427. HELD đã hết hạn vẫn phải tải được; đọc không tự nhả chỗ,
     * tính lại hạn, cộng đệm hoặc chuyển trạng thái.
     *
     * <p>Không tìm thấy trả rỗng; lỗi truy xuất hoặc khôi phục phải báo lỗi.
     * Đọc không khóa bản ghi; thao tác ghi tiếp theo phải kiểm trạng thái cũ
     * ngay trong UPDATE để bảo vệ dưới đồng thời.
     *
     * @param code mã reservation có nội dung, đã được application kiểm tra
     * @return aggregate nếu có, Optional rỗng nếu không có; không trả null
     */
    Optional<Reservation> loadAggregate(String code);

    /**
     * Đọc ID xe có khóa đang chặn chồng lên khoảng đã cộng đệm theo BR-104.
     *
     * <p>Tính HELD, CONFIRMED, IN_USE, BLOCKED và COMPLETED; không lọc theo
     * hạn HELD vì trước khi được nhả bản ghi vẫn chặn ở CSDL theo BR-103.
     * Không cộng đệm lần nữa, không khóa bản ghi hay cập nhật trạng thái.
     *
     * @param bufferedPeriod khoảng hữu hạn [) đã cộng đệm và kiểm tra
     * @param candidateIds các ID xe dương đã kiểm tra, không null
     * @return tập xe bận chỉ thuộc tập ứng viên; tập rỗng nếu không có
     */
    Set<Long> findBusyVehicleIds(ReservationPeriod bufferedPeriod, Collection<Long> candidateIds);
}
