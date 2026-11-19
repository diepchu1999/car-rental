# ADR-0014: Đơn đóng băng mọi giá trị tại thời điểm tạo

- Trạng thái: ACCEPTED · 27/08/2026

## Bối cảnh
Ba quy tắc độc lập cùng nói một điều:
- BR-122 — đơn đóng băng **giá** tại thời điểm đặt.
- BR-208 — đơn đóng băng **mức hoa hồng** tại thời điểm tạo.
- BR-225 — đơn đóng băng **tham số cấu hình** đang áp dụng.

## Quyết định
Khi tạo đơn, hệ thống **sao chép vào chính bản ghi đơn** mọi giá trị cần để tính tiền và xử lý sau
này: giá từng ngày theo loại ngày, mức chiết khấu, phí tài xế, phí giao xe, phí bảo hiểm, giới hạn
km, khoảng đệm, mức hoa hồng, và các mức phí phạt sẽ dùng khi quyết toán.

Quyết toán khi trả xe đọc từ **bản sao trong đơn**, không đọc lại từ bảng cấu hình.

## Lý do
Không có nó, mỗi lần admin đổi cấu hình sẽ âm thầm tính lại những đơn cũ chưa quyết toán. Đó là loại
sai **không báo lỗi** — nó chỉ lộ ra khi khách cãi hoá đơn hoặc khi đối soát tiền với đối tác, tức là
muộn nhất có thể và ở chỗ tổn hại nhất.

Ví dụ cụ thể: khách đặt xe tháng 8 với hoa hồng 10%. Tháng 9 admin đổi lên 15% vì mùa cao điểm.
Nếu đọc cấu hình lúc chi trả, chủ xe bị trừ 15% cho một đơn đã thoả thuận 10% — và chủ xe sẽ phát
hiện ra.

## Hệ quả
- Bản ghi đơn to hơn. Chấp nhận — dung lượng rẻ, tranh chấp thì không.
- BR-427 (gia hạn) tuân theo: gia hạn tạo **bản ghi riêng** với bộ giá trị của thời điểm gia hạn,
  không sửa bộ giá trị của đơn gốc.
- Kết hợp với ADR-0013: cấu hình giữ lịch sử, đơn giữ bản sao. Hai lớp bảo vệ cho cùng một mục đích.

## Làm rõ 09/11/2026 — lát cắt 1 chưa đóng băng giá trị quyết toán

Lát cắt 1 (Task 9) đóng băng: tiền thuê theo từng dòng, bảo hiểm, tổng, tỷ lệ cọc, cọc, phần còn lại
(BR-201), giá ngày, điều kiện thuê đã áp (khoảng đệm…) và hạn giữ chỗ.

**Chưa đóng băng** giới hạn km (BR-115, BR-401), mức phí trả trễ (BR-118, BR-408) và chính sách nhiên liệu
(BR-409, BR-414, BR-117) — chưa có cổng nào cung cấp các giá trị này. Lát cắt 2 (quyết toán) bổ sung.

Hệ quả bắt buộc:
- **Đơn thiếu giá trị quyết toán thì không được quyết toán** — báo lỗi rõ ràng, **không** đọc cấu hình hiện
  tại thay vào. Đọc cấu hình hiện tại chính là lỗi quyết định này sinh ra để tránh.
- Chấp nhận được vì mọi đơn tạo trong lát cắt 1 là dữ liệu thử ở local, chưa có khách thật.
- **Trước khi chạy với khách thật, đơn phải đóng băng đủ mọi giá trị quyết toán.**

