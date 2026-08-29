# Mobile Guideline

> Nền: ADR-0009 (React Native) · ADR-0006 (auth).

## 1. Phạm vi
Một ứng dụng React Native cho iOS và Android. Giai đoạn 1 phục vụ `CUSTOMER`; giai đoạn 2 thêm chế
độ `HOST` trong **cùng một app**.

## 2. Vì sao cần app, không chỉ web responsive
Bốn năng lực chỉ app làm tốt, và cả bốn đều là nghiệp vụ đã có quy tắc:

| Năng lực | Quy tắc |
|---|---|
| Chụp ảnh tình trạng xe kèm mốc thời gian và toạ độ đáng tin | BR-407 — và là **bằng chứng pháp lý** (BR-702) |
| Ký biên bản bàn giao tại chỗ, ngoài đường | BR-205 |
| Push cho thông báo nhạy thời gian | BR-1002 — giữ chỗ chỉ 1 tiếng, email không kịp |
| Định vị cho tài xế và nhân viên giao xe | BR-1006 |

Tính năng khác (tìm xe, xem chi tiết, lịch sử đơn) web làm được — app làm vì tiện, không vì bắt buộc.

## 3. API dùng chung với web
Mobile gọi `/api/v1/client/**`, **không có namespace riêng** (api-guideline §2).

## 4. Versioning là ràng buộc cứng
Người dùng không cập nhật app ngay. Backend phải chịu được client cũ nhiều tháng.

Hệ quả cho app: **client phải bỏ qua field lạ và giá trị enum lạ thay vì hỏng.** Điều này phải đúng
**từ bản phát hành đầu tiên** — không trang bị sau được, vì bản đầu tiên chính là bản tồn tại lâu
nhất ngoài tự nhiên.

## 5. Mạng di động không đáng tin
- Mọi thao tác ghi quan trọng gửi kèm `Idempotency-Key`. Client thử lại là chuyện bình thường;
  không có key thì thử lại nghĩa là đặt hai đơn.
- Upload ảnh bàn giao phải chịu được gián đoạn: nén trước khi gửi, gửi từng ảnh, cho phép tiếp tục.
- Danh sách dùng cursor pagination, không offset.

## 6. Ảnh bàn giao
- Nén ở client trước khi upload, nhưng **giữ mốc thời gian và toạ độ**.
- **Không cho chọn ảnh từ thư viện** cho biên bản bàn giao — chỉ chụp trực tiếp. Ảnh từ thư viện
  phá huỷ giá trị làm bằng chứng.
- Upload qua liên kết ký sẵn, không đẩy nhị phân qua API backend.

## 7. Auth
PKCE. Token ở Keychain/Keystore. Deep link redirect URI đăng ký chính xác.
Chi tiết ở [`security-guideline.md`](security-guideline.md) §1.

## 8. Đăng nhập bằng OTP
Luồng chính là số điện thoại + OTP (BR-120). Màn hình OTP phải xử lý: gửi lại có đếm ngược,
giới hạn số lần thử, và trường hợp SMS tới muộn hơn thời hạn.

## 9. Chưa chốt
| Nội dung |
|---|
| Nhà cung cấp push (FCM/APNs trực tiếp hay dịch vụ trung gian) |
| Có cần bản đồ trong app không, và nhà cung cấp nào |
| Có yêu cầu hoạt động offline khi giao xe ở nơi sóng yếu không |
