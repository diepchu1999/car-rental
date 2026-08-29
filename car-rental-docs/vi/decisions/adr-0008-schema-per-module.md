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

## Hệ quả
- Mất kiểm tra toàn vẹn tự động cho tham chiếu chéo module. Đổi lấy ranh giới thật.
- Truy vấn cần dữ liệu nhiều vùng dùng read model, không JOIN thẳng qua schema.
- Migration Flyway đặt tên có tiền tố module để tránh đụng nhau.
- SQL luôn ghi rõ schema.
