# ADR-0006: Keycloak cho xác thực, viết thêm Authenticator cho OTP qua SMS

- Trạng thái: ACCEPTED · 27/08/2026 · Tech Owner

## Bối cảnh
BR-120: tài khoản khách đăng ký bằng **số điện thoại**, một số một tài khoản. Đăng nhập bằng OTP qua
SMS. Hệ thống có 12 vai (BR-801) và ba loại client, trong đó có mobile.

Keycloak **không hỗ trợ sẵn OTP qua SMS** — nó chỉ có TOTP kiểu ứng dụng xác thực.

## Quyết định
Dùng Keycloak làm Identity Provider (OIDC), và **viết một Authenticator SPI bằng Java** cho luồng
OTP qua SMS.

Backend là resource server, xác thực JWT bearer. Keycloak giữ danh tính và vai thô; phân quyền theo
dữ liệu (BR-802: ranh giới chi nhánh) nằm ở tầng ứng dụng.

## Lý do
Cái phải tự viết là **một luồng đăng nhập**. Cái được miễn phí là phần dễ làm sai và tốn kém khi
sai: xoay refresh token, PKCE cho client công khai, thu hồi phiên, quản lý vai, và màn hình quản trị.

Hệ thống này giữ CCCD, giấy phép lái xe và dữ liệu thanh toán. Tự viết toàn bộ tầng xác thực để
tránh viết một Authenticator là đánh đổi sai chiều.

Thêm nữa, Keycloak SPI là Java — cùng ngôn ngữ với backend (ADR-0002), nên không cần kỹ năng thứ hai.

## Hệ quả
- Ba client trong realm: `customer-web`, `admin-web` (public + PKCE), `mobile` (public + PKCE, redirect qua deep link).
- **Không dùng Resource Owner Password flow cho mobile** — đã bị deprecated và bắt app cầm mật khẩu.
- Token trên mobile lưu ở Keychain/Keystore, không lưu ở AsyncStorage.
- Realm export vào repo để tái lập được ở local.
- Adapter gửi SMS ở local là mock — in OTP ra log.

## Làm rõ 12/10/2026 — luồng tài khoản cho lát cắt 1

**Ai giữ gì.** Keycloak giữ mật khẩu, luồng OTP và vai (BR-801). Schema `identity` của backend giữ tài
khoản, bản sao đồng ý điều khoản (BR-120) và chi nhánh của nhân viên (BR-803, BR-807). Token có vai
nhân viên mà chưa gán chi nhánh thì backend **từ chối**.

**Khách: đăng ký là lần đăng nhập OTP đầu tiên.** Số điện thoại chưa có người dùng thì luồng Keycloak
tạo người dùng, sau khi khách đồng ý điều khoản (BR-120). Màn hình đồng ý nằm **trong luồng Keycloak** —
đó là chỗ duy nhất chạy trước khi tài khoản tồn tại. Backend tạo `identity.account` ở **lần gọi API đầu
tiên có token**, an toàn khi hai request đầu tới cùng lúc.

**Nhân viên: mật khẩu cộng OTP** (BR-809) — luồng đăng nhập riêng, gắn với client `admin-web`.

**Tạo nhân viên khi chưa có `admin-web`.** `ADMIN` tạo người dùng, đặt mật khẩu và gán vai trong màn
hình quản trị có sẵn của Keycloak; gán chi nhánh qua API backend `/api/v1/admin/...`. Tài khoản `ADMIN`
đầu tiên chỉ có trong realm export dùng cho local, không bao giờ trong môi trường thật.

**Mã nguồn.** Phần mở rộng Keycloak ở `car-rental-keycloak/` (development-guideline §5); realm ở
`infra/keycloak/realm-export.json`.
