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
