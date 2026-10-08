# ADR-0008: Mỗi module một schema, không FK chéo module

- Trạng thái: ACCEPTED · 27/08/2026

## Quyết định
Mỗi vùng nghiệp vụ sở hữu một schema PostgreSQL riêng. Không tạo khoá ngoại trỏ sang schema khác.
Tham chiếu chéo module lưu bằng ID và mã nghiệp vụ, toàn vẹn bảo đảm ở tầng ứng dụng.

## Lý do
Khoá ngoại chéo module là cách ranh giới chết âm thầm: nó biến hai vùng thành một khối không tách
được, và khiến việc xoá hay đổi dữ liệu bên này phụ thuộc lược đồ bên kia.

Có một hệ quả tốt bất ngờ ở dự án này: vì `availability.reservation` chỉ giữ `vehicle_id` trần,
**vùng `availability` test được độc lập** — bắn 50 luồng đồng thời vào `vehicle_id = 1` mà không cần
một chiếc xe thật nào trong CSDL. Phần rủi ro nhất hệ thống trở thành phần dễ kiểm chứng nhất.

## Làm rõ 07/10/2026 — "ID và mã nghiệp vụ" nghĩa là gì

Không đổi quyết định: vẫn không khoá ngoại chéo module, toàn vẹn vẫn ở tầng ứng dụng. Nhưng câu
"lưu bằng ID **và** mã nghiệp vụ" ở trên mâu thuẫn với chính đoạn Lý do — đoạn đó khen
`availability.reservation` giữ `vehicle_id` trần — và code chưa bao giờ làm theo nó:

| Tham chiếu | Lưu gì | Dùng vào việc |
|---|---|---|
| `vehicle.vehicle.branch_id` | Chỉ ID | Ghép xe với chi nhánh trong tìm kiếm |
| `availability.reservation.vehicle_id` | Chỉ ID | Ràng buộc chống trùng lịch theo xe |
| `availability.reservation.booking_code` | Chỉ mã | Đối chiếu khoá lịch với đơn; không dùng để ghép |

Nói chính xác:

- Lưu **ít nhất một định danh không bao giờ đổi** của bản ghi bên kia: **ID** khi cần ghép hay ràng
  buộc theo nó, **mã nghiệp vụ** khi chỉ để đối chiếu hay hiển thị.
- Lưu **cả hai** khi module thật sự cần cả hai mà không muốn gọi sang module kia. Sao chép an toàn vì
  ID và mã nghiệp vụ đều không bao giờ đổi (database-guideline §2). Không cần thì đừng lưu — mỗi bản sao
  là thêm một thứ phải đọc hiểu.
- Chỉ lưu ID mà phải trả mã ra ngoài — R12 cấm trả ID số — thì **tra mã qua `api` của module sở hữu**.
  Ví dụ: `vehicle` lấy mã chi nhánh qua `BranchDirectory` khi trả response (Task 6b). Không JOIN chéo
  schema.

## Hệ quả
- Mất kiểm tra toàn vẹn tự động cho tham chiếu chéo module. Đổi lấy ranh giới thật.
- Truy vấn cần dữ liệu nhiều vùng dùng read model, không JOIN thẳng qua schema.
- Migration Flyway đặt tên có tiền tố module để tránh đụng nhau.
- SQL luôn ghi rõ schema.
