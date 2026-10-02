package com.carrental.availability.api;

import com.carrental.shared.error.DomainException;

import java.time.Duration;
import java.util.Collection;
import java.util.Set;

/**
 * Công bố cổng duy nhất để module khác đọc và thay đổi lịch xe.
 *
 * <p>Theo BR-104 và ADR-0005, availability là nguồn sự thật duy nhất
 * về lịch bận. Module gọi không truy cập trực tiếp bảng reservation.
 *
 * <p>Các thao tác ghi tham gia transaction của bên gọi nếu đã có,
 * không tạo transaction độc lập bằng REQUIRES_NEW.
 * Giữ chỗ và tạo đơn phải có thể commit hoặc rollback cùng nhau.
 *
 * <p>Đầu vào chỉ chứa định danh xe và dữ liệu lịch.
 * Cổng không nhận loại sở hữu, loại gói thuê hoặc thông tin chi nhánh.
 *
 * <p>Đây là hợp đồng giao tiếp nội bộ, không phải REST API.
 * Module khác chỉ cần import các kiểu trong availability.api.
 */
public interface AvailabilityDirectory {

    /**
     * Giữ chỗ cho một đơn thuê theo BR-102, BR-103 và BR-104.
     *
     * <p>rentalPeriod là khoảng thuê hữu hạn chưa cộng đệm.
     * Availability cộng buffer vào cuối khoảng trước khi ghi,
     * theo BR-109 và BR-116. Bên gọi không cộng đệm trước.
     *
     * <p>Thời hạn giữ chỗ lấy từ cấu hình hệ thống theo BR-225;
     * bên gọi không được truyền hoặc chọn thời hạn.
     *
     * <p>Ghi trực tiếp và để ràng buộc PostgreSQL quyết định
     * có chồng lịch hay không. Không đọc kiểm trống trước khi ghi.
     *
     * @param vehicleId định danh xe lớn hơn không
     * @param rentalPeriod khoảng thuê hữu hạn, bắt buộc
     * @param buffer độ dài đệm đã được bên gọi phân giải, không âm
     * @param bookingCode mã đơn thuê có nội dung
     * @return định danh khóa RENTAL/HELD vừa được ghi
     * @throws DomainException nếu đầu vào không hợp lệ
     *                         hoặc xe không còn trống trong khoảng yêu cầu
     */
    ReservationRef hold(
            long vehicleId,
            Period rentalPeriod,
            Duration buffer,
            String bookingCode
    );

    /**
     * Tạo khóa vận hành trực tiếp ở trạng thái BLOCKED.
     *
     * <p>Áp dụng BR-007, BR-011, BR-012, BR-015 và BR-104.
     * Chỉ COMPLIANCE_HOLD được nhận khoảng không chặn trên.
     *
     * <p>period là khoảng vận hành cần khóa, không tự cộng đệm thuê xe.
     * Lý do được lưu nguyên văn, không nhận rồi bỏ đi.
     *
     * @param vehicleId định danh xe lớn hơn không
     * @param period khoảng cần khóa, bắt buộc
     * @param kind nguyên nhân khóa vận hành, bắt buộc
     * @param reason lý do khóa, có thể null theo DDL
     * @return định danh khóa vận hành vừa được ghi
     * @throws DomainException nếu đầu vào không hợp lệ
     *                         hoặc khoảng yêu cầu chồng khóa đang chặn
     */
    ReservationRef block(
            long vehicleId,
            Period period,
            BlockKind kind,
            String reason
    );

    /**
     * Chuyển chỗ giữ còn hạn từ HELD sang CONFIRMED.
     *
     * <p>Theo BR-103, tại đúng thời điểm hết hạn không được xác nhận.
     * Bên gọi chịu trách nhiệm xác nhận cọc và điều phối transaction
     * giữa thanh toán, đơn thuê và khóa lịch.
     *
     * @param reservationCode mã reservation có nội dung, không phải mã đơn
     * @throws DomainException nếu mã không hợp lệ, không tìm thấy khóa,
     *                         sai trạng thái, đã hết hạn
     *                         hoặc có xung đột cập nhật đồng thời
     */
    void confirm(String reservationCode);

    /**
     * Chuyển HELD hoặc CONFIRMED sang RELEASED theo BR-103, BR-304.
     *
     * <p>RELEASED không còn tham gia chặn lịch.
     * Không dùng thao tác này để kết thúc chuyến đang chạy
     * hoặc hoàn tất khóa vận hành.
     *
     * @param reservationCode mã reservation có nội dung, không phải mã đơn
     * @throws DomainException nếu mã không hợp lệ, không tìm thấy khóa,
     *                         sai trạng thái hoặc có xung đột cập nhật
     */
    void release(String reservationCode);

    /**
     * Chuyển CONFIRMED sang IN_USE khi xe được bàn giao.
     *
     * <p>Tuân theo status-flow §2. Các điều kiện thanh toán,
     * giấy tờ khách và biên bản bàn giao thuộc bên gọi.
     * Availability không truy cập các module đó để kiểm lại.
     *
     * @param reservationCode mã reservation có nội dung, không phải mã đơn
     * @throws DomainException nếu mã không hợp lệ, không tìm thấy khóa,
     *                         sai trạng thái hoặc có xung đột cập nhật
     */
    void markInUse(String reservationCode);

    /**
     * Chuyển IN_USE hoặc BLOCKED sang COMPLETED theo status-flow §2.
     *
     * <p>Giữ nguyên khoảng đã lưu. COMPLETED vẫn chặn trong khoảng đó
     * để bảo toàn đệm theo BR-109, BR-116 và database-guideline §4.
     * Hoàn tất không đồng nghĩa với giải phóng ngay toàn bộ khoảng.
     *
     * <p>Không co khoảng theo giờ trả thực tế trong task hiện tại.
     *
     * @param reservationCode mã reservation có nội dung, không phải mã đơn
     * @throws DomainException nếu mã không hợp lệ, không tìm thấy khóa,
     *                         sai trạng thái hoặc có xung đột cập nhật
     */
    void complete(String reservationCode);

    /**
     * Tìm những xe trong tập ứng viên đang bận đối với khoảng thuê yêu cầu.
     *
     * <p>period là khoảng thuê hữu hạn chưa cộng đệm.
     * Availability mở rộng cuối khoảng bằng buffer giống thao tác hold,
     * rồi kiểm chồng với lịch đã lưu theo BR-104, BR-109 và BR-116.
     *
     * <p>Tính các trạng thái HELD, CONFIRMED, IN_USE, BLOCKED
     * và COMPLETED là đang chặn. HELD đã hết hạn nhưng chưa được
     * job giải phóng vẫn phải tính bận theo BR-103.
     *
     * <p>Kết quả chỉ phản ánh dữ liệu tại thời điểm truy vấn.
     * Không được dùng kết quả này thay cho ràng buộc PostgreSQL
     * khi giữ chỗ; một thao tác khác có thể ghi lịch ngay sau truy vấn.
     *
     * @param period khoảng thuê hữu hạn, bắt buộc
     * @param buffer độ dài đệm đã được phân giải, không âm
     * @param candidateIds tập định danh xe cần kiểm tra, không null;
     *                     mỗi phần tử phải khác null và lớn hơn không
     * @return tập định danh xe bận, không trùng và chỉ thuộc tập ứng viên;
     *         tập rỗng nếu không có xe bận hoặc tập ứng viên rỗng
     * @throws DomainException nếu đầu vào không hợp lệ
     */
    Set<Long> findBusyVehicleIds(
            Period period,
            Duration buffer,
            Collection<Long> candidateIds
    );
}