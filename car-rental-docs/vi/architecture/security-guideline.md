# Security Guideline

> Nền: ADR-0006 (Keycloak + OTP qua SMS) · ADR-0015 (bằng chứng bất biến).

## 1. Xác thực

Keycloak, OIDC. Backend là resource server xác thực JWT bearer.

| Client | Loại | Flow |
|---|---|---|
| `customer-web` | public | Authorization Code + PKCE |
| `admin-web` | public | Authorization Code + PKCE |
| `mobile` | public | Authorization Code + PKCE, redirect qua deep link |

**Không dùng Resource Owner Password flow.** Nó bắt app cầm mật khẩu người dùng, đã bị deprecated,
và làm mất khả năng bật xác thực nhiều lớp sau này.

### OTP qua SMS
Keycloak không hỗ trợ sẵn — phải viết Authenticator SPI bằng Java (ADR-0006). Yêu cầu:
- Mã OTP có hạn ngắn, dùng một lần, **tiêu thụ nguyên tử** (không cho dùng lại kể cả khi gửi song song).
- Giới hạn số lần gửi và số lần thử sai theo số điện thoại và theo IP.
- Ở local, adapter gửi SMS là mock — in mã ra log, **không** gửi thật.

### Token trên mobile
- Lưu ở **Keychain (iOS) / Keystore (Android)**. Không lưu ở AsyncStorage hay SharedPreferences.
- Refresh token có xoay vòng. Phát hiện dùng lại token cũ ⇒ thu hồi cả phiên.
- Access token có hạn ngắn; giữ phiên bằng refresh token, không bằng access token dài hạn.
- Deep link redirect URI đăng ký chính xác, **không dùng ký tự đại diện**.

## 2. Phân quyền

Keycloak giữ vai thô (12 vai, BR-801). Phân quyền theo dữ liệu nằm ở tầng ứng dụng.

### Ranh giới chi nhánh — BR-802, BR-803
Người dùng thuộc một chi nhánh **không xem, không sửa** dữ liệu của chi nhánh khác. Chỉ `ADMIN`
xuyên chi nhánh. Đây là kiểm tra ở **use case**, không phải bộ lọc ở giao diện.

### Chống truy cập chéo — rủi ro số một của hệ thống này

Nhiều bên cùng nhìn vào **một đơn thuê**: khách, tài xế, nhân viên giao xe, chi nhánh, kế toán.
Đó chính là điều kiện sinh ra lỗi truy cập tài nguyên của người khác bằng cách đổi mã trên URL.

Bốn quy tắc bắt buộc:
1. **Không bao giờ tin mã trên URL là đủ.** Luôn kiểm chủ thể trong token có quyền với tài nguyên đó
   không, ngay trong use case.
2. Dùng **mã nghiệp vụ**, không dùng khoá chính số tuần tự.
3. Kiểm quyền ở **application service** — không ở controller, không ở frontend.
4. Câu phải trả lời cho mọi endpoint mới: *"nếu một người dùng hợp lệ gọi endpoint này với mã tài
   nguyên của người khác thì sao?"* Chưa trả lời được thì endpoint chưa xong.

### Cùng một đơn, mỗi vai thấy khác nhau

| Trường | Khách | Tài xế | NV giao xe | Chi nhánh | Kế toán |
|---|---|---|---|---|---|
| Số điện thoại bên kia | Sau khi xác nhận | Của khách, chỉ ngày chạy | Của khách, chỉ ngày giao | Có | Có |
| Địa chỉ giao xe | Có | Chỉ ngày chạy | Chỉ ngày giao | Có | Không |
| Ảnh CCCD / GPLX của khách | Của mình | **Không** | **Không** | Có | Không |
| Tổng tiền khách trả | Có | **Không** | **Không** | Có | Có |
| Hoa hồng nền tảng *(GĐ2)* | **Không** | **Không** | **Không** | Không | Có |

Ba dòng cuối là chính sách chống rò rỉ. Cần response khác nhau theo audience, **không** trả một
object đầy đủ rồi để giao diện tự ẩn.

## 3. Dữ liệu nhạy cảm

Hệ thống lưu CCCD, GPLX, ảnh tình trạng xe, vị trí, và dữ liệu GPS liên tục của xe công ty.

- File nhị phân ở object storage, CSDL chỉ giữ khoá.
- Truy cập ảnh giấy tờ qua **liên kết ký ngắn hạn**, sinh mỗi lần, không cache, không công khai.
- Số CCCD và GPLX: hash để tìm kiếm, mã hoá bản rõ.
- **Audit mọi lần đọc giấy tờ của người khác** — ai đọc, đọc của ai, lúc nào.
- Vị trí xe và tài xế chỉ lộ khi đang có chuyến. Dữ liệu GPS lịch sử giữ đúng thời hạn cần cho
  tranh chấp và phạt nguội, không giữ lâu hơn.

### Log — không để dữ liệu nhạy cảm lọt qua đường log

- **Log SQL kèm giá trị tham số chỉ bật ở local**, qua profile `sql-log`. Mặc định tắt, test tắt.
  Áp cho mọi cách làm việc này: P6Spy, hay log `TRACE` của `StatementCreatorUtils`.
- **Không bao giờ bật ở môi trường có dữ liệu thật.** Giá trị tham số là số điện thoại, mã đơn, toạ độ,
  số CCCD đã mã hoá — log là nơi dữ liệu bị đọc bởi người không có quyền đọc bảng gốc. Khi dựng môi
  trường thật, kiểm rằng profile `sql-log` **không thể** được bật ở đó.
- **Lỗi 500: chi tiết chỉ nằm ở log máy chủ.** Stack trace, tên class, số dòng, câu SQL không bao giờ
  vào response gửi khách — response chỉ có mã lỗi chung. Đã khoá bằng test
  `hidesInternalDetailsForUnexpectedFailure`.

### Xoá tài khoản — ngoại lệ có căn cứ
Khách xoá tài khoản thì tài khoản ngừng hoạt động, nhưng **hồ sơ bằng chứng của đơn đã hoàn tất
được giữ** (BR-123, ADR-0015). Đây là nghĩa vụ pháp lý theo Nghị định 336/2025.
Chính sách dữ liệu cá nhân phải nêu rõ ngoại lệ này để khách biết trước.

## 4. Bảo mật thanh toán

- **Không bao giờ tin số tiền client gửi.** Luôn tính lại từ `quoteCode` (BR-216).
- Xác nhận thanh toán thủ công vẫn cần khoá idempotency và audit — con người đang đóng vai cổng
  thanh toán, và con người bấm hai lần (ADR-0011).
- Xác nhận thay của `BRANCH_MANAGER` (BR-217) phải ghi audit riêng, nêu rõ thay cho ai và vì sao.
- Không log số tài khoản đầy đủ, không log toàn bộ payload thanh toán.
- Khi tích hợp cổng thật: xác thực chữ ký callback trước khi xử lý, ghi callback vào bảng riêng
  trước, chống phát lại bằng `UNIQUE (provider, provider_transaction_id)`.

## 5. Upload file

- Giới hạn kích thước và kiểu MIME ở server, **không tin `Content-Type` client gửi**.
- Sinh lại tên file, không dùng tên client đặt.
- Ảnh bàn giao: **giữ mốc thời gian và toạ độ** — đó là phần giá trị pháp lý (ADR-0015).
- Kiểm kích thước ảnh trước khi giải nén, tránh ảnh phình.
- Upload qua liên kết ký sẵn, không đẩy nhị phân qua API backend.

## 6. Giới hạn tần suất

| Điểm | Vì sao |
|---|---|
| Gửi OTP | Chi phí SMS thật, và là cửa cho tấn công gây tốn tiền |
| Xác minh OTP | Chống dò mã |
| Tìm kiếm xe | Tốn tài nguyên, và dễ bị quét lấy toàn bộ danh mục |
| Tạo báo giá | Như trên |
| Đăng nhập | Chống dò |

## 7. Checklist cho mỗi endpoint mới

1. Audience nào gọi được? (`public` / `client` / `admin`)
2. Vai nào được phép?
3. Người dùng hợp lệ gọi với mã tài nguyên của người khác thì sao?
4. Trường nào audience này **không** được thấy?
5. Có ghi tiền hoặc ghi lịch không? Đã có idempotency và ràng buộc tranh chấp chưa?
6. Có ghi audit không?
