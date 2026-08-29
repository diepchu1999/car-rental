# ADR-0009: React Native cho ứng dụng mobile

- Trạng thái: ACCEPTED · 27/08/2026 · Tech Owner

## Quyết định
Một ứng dụng React Native cho cả iOS và Android. Giai đoạn 1 phục vụ `CUSTOMER`; giai đoạn 2 thêm
chế độ `HOST` trong **cùng một app**, không tách app thứ hai.

## Lý do
Đội đã dùng React + TypeScript cho web, nên dùng chung được kiểu dữ liệu API, tầng gọi API, quy ước
quản lý state và thói quen review. Chọn Flutter sẽ bắt đội học một ngôn ngữ và hệ sinh thái mới
song song với việc học 149 quy tắc nghiệp vụ.

Chủ xe cá nhân thường cũng là khách thuê — hai app nhân đôi chi phí phát hành và duyệt store cho
một startup, đổi lại rất ít.

## Vì sao cần app, không chỉ web
Bốn năng lực chỉ app làm tốt, và chúng đều là nghiệp vụ đã có quy tắc:
- Chụp ảnh tình trạng xe kèm mốc thời gian và toạ độ đáng tin — BR-407, và nó là **bằng chứng pháp lý** (BR-702).
- Ký biên bản bàn giao tại chỗ, ngoài đường — BR-205.
- Push cho các thông báo nhạy thời gian — BR-1002, và với thời hạn giữ chỗ 1 tiếng thì email không kịp.
- Định vị cho tài xế và nhân viên giao xe.

## Hệ quả
- Sinh client API và kiểu dữ liệu từ đặc tả OpenAPI để web và mobile không lệch nhau.
- Auth theo ADR-0006: PKCE, token ở Keychain/Keystore.
- **Versioning API nghiêm ngặt**: người dùng không cập nhật app ngay, backend phải chịu được client
  cũ. Trong cùng một phiên bản chỉ được **thêm**, không xoá field, không đổi kiểu, không đổi nghĩa.
- Client phải bỏ qua field lạ và giá trị enum lạ thay vì hỏng — phải đúng từ bản đầu tiên.
