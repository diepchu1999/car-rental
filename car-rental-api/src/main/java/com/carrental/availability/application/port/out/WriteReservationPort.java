package com.carrental.availability.application.port.out;

import com.carrental.availability.domain.Reservation;
import com.carrental.availability.domain.ReservationStatus;

import java.time.Instant;
import java.util.OptionalLong;

/**
 * Cổng đầu ra cho việc ghi dữ liệu khóa lịch.
 *
 * <p>Nhận aggregate domain, không nhận read view theo quy tắc R4.
 * Chống trùng lịch dựa vào ràng buộc của PostgreSQL theo BR-104
 * và ADR-0005, không dựa vào truy vấn kiểm tra xe trống trước khi ghi.
 *
 * <p>Application service sở hữu ranh giới transaction.
 * Phần hiện thực cổng tham gia transaction hiện tại,
 * không tự commit hoặc mở transaction độc lập.
 */
public interface WriteReservationPort {

    /**
     * Chèn một khóa lịch mới và trả về khóa chính do database sinh.
     *
     * <p>Aggregate đã chứa mã nghiệp vụ, khoảng khóa lịch,
     * trạng thái và các mốc thời gian cần lưu.
     * Với khóa thuê xe, application đã áp dụng khoảng đệm
     * theo BR-109 hoặc BR-116 trước khi gọi cổng này.
     * Adapter không cộng đệm hoặc tính lại hạn giữ chỗ.
     *
     * <p>Nếu vi phạm riêng ràng buộc duy nhất uq_reservation_code,
     * không chèn và trả về OptionalLong rỗng để application có thể
     * sinh mã khác. Không cập nhật bản ghi đang tồn tại.
     * Việc xử lý trùng mã không được làm hỏng transaction hiện tại.
     *
     * <p>Trùng lịch phải được báo bằng ReservationOverlapException,
     * không được chuyển thành kết quả rỗng hoặc thử lại bằng mã khác.
     * Các lỗi lưu trữ khác phải được truyền ra ngoài.
     *
     * <p>Adapter tự xử lý deadlock do chèn đồng thời: mỗi lần chèn dùng savepoint,
     * chỉ thử lại SQLSTATE 40P01 sau khi rollback savepoint, tối đa ba lần tính cả lần đầu.
     * Mọi lần dùng nguyên aggregate (mã, khoảng, hạn không đổi). Hết lượt phải truyền
     * nguyên lỗi deadlock gốc, không đổi thành lỗi xe bận. Transaction vật lý vẫn
     * thuộc bên gọi; đây không phải REQUIRES_NEW hoặc thử lại toàn bộ use case.
     *
     * <p>ID trả về chỉ xác nhận thao tác chèn trong transaction hiện tại.
     * Nếu transaction bên gọi rollback, bản ghi cũng phải rollback theo.
     *
     * @param reservation khóa lịch hợp lệ cần chèn, không được null
     * @return ID của bản ghi mới; rỗng chỉ khi không chèn do trùng mã nghiệp vụ
     * @throws ReservationOverlapException nếu database xác nhận khóa lịch
     *                                     chồng thời gian trên cùng một xe
     */
    OptionalLong insert(Reservation reservation);

    /**
     * Ghi trạng thái và mốc thay đổi chỉ khi mã và trạng thái cũ cùng khớp.
     *
     * <p>Application phải gọi hành vi domain trước để kiểm cạnh chuyển và
     * hạn xác nhận theo BR-103, status-flow §2. Cổng này chỉ bảo vệ việc
     * lưu kết quả trước tranh chấp, không tự quyết định chuyển trạng thái.
     * Điều kiện trạng thái cũ nằm trong cùng UPDATE, không kiểm riêng trước.
     *
     * <p>Không sửa period, hạn giữ chỗ, thời điểm tạo, mã, xe, loại khóa,
     * mã đơn hoặc lý do. Đặc biệt COMPLETED không làm mất khoảng đệm
     * theo BR-109, BR-116. Không tự commit hoặc mở transaction độc lập.
     *
     * @param code mã reservation có nội dung
     * @param expectedStatus trạng thái vừa được application đọc
     * @param newStatus trạng thái đã được domain cho phép
     * @param changedAt thời điểm chuyển trạng thái lấy từ Clock chung
     * @return true nếu cập nhật một dòng; false nếu mã hoặc trạng thái cũ không khớp
     */
    boolean updateStatus(String code, ReservationStatus expectedStatus,
                         ReservationStatus newStatus, Instant changedAt);

    /**
     * Nhả nguyên tử các bản ghi còn HELD với hold_expires_at <= expiredAt theo BR-103.
     *
     * <p>Đây là chuyển trạng thái theo tập: điều kiện trạng thái và hạn nằm trong
     * cùng UPDATE, không tải danh sách rồi gọi release từng bản ghi. Không nhả
     * CONFIRMED nếu xác nhận thắng tranh chấp. Chỉ sửa status và status_changed_at;
     * không xóa hồ sơ, đổi khoảng hoặc tính lại hạn. Chạy lại không sửa bản đã nhả.
     *
     * @param expiredAt mốc dọn đồng thời là mốc chuyển trạng thái, từ Clock chung
     * @return số bản ghi được nhả; số không là kết quả hợp lệ
     */
    int releaseExpiredHolds(Instant expiredAt);
}
