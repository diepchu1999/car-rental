# Phần mở rộng Keycloak

Provider Java 21 cho kho OTP BR-129: dùng connection, transaction và Liquibase của Keycloak.
Không có DataSource riêng. Metadata JPA chỉ phục vụ cơ chế mở rộng model Keycloak; thao tác kho dùng
prepared SQL. Tài liệu cơ chế mở rộng:
[Keycloak 26.7.2 — custom JPA entities](https://www.keycloak.org/docs/26.7.2/server_development/#_extensions_jpa).

## Build và kiểm chứng

Cần Docker đang chạy và JDK 21 trở lên. Wrapper dùng bản Maven đã ghim của `car-rental-api`.
Từ thư mục `car-rental-keycloak/`:

```sh
chmod +x mvnw
./mvnw clean verify
```

Unit test chạy trước; Failsafe chạy test artifact và Keycloak/PostgreSQL thật sau khi package.
Test lấy image/platform từ `.env.example`, không đọc `.env`, không dùng CSDL local.
Container test có cổng ngẫu nhiên; dữ liệu là giả và tự dọn khi kết thúc.
Lần đầu cần tải image và dependency. Không tắt Docker hay dùng `-DskipTests` để thay cho kiểm chứng.

Chạy riêng cuộc đua dùng OTP một lần (vẫn package lại JAR):

```sh
./mvnw '-Dit.test=KeycloakOtpIT#consumesOnceUnderDeterministicRace' verify
```

## Artifact

- `target/car-rental-keycloak-0.0.1-SNAPSHOT.jar`: provider lõi, không có SMS giả hoặc fixture HTTP.
- `target/car-rental-keycloak-0.0.1-SNAPSHOT-local-sms.jar`: chỉ adapter SMS giả; bắt buộc Keycloak
  `start-dev`. Chỉ dùng dữ liệu local giả; OTP xuất hiện trong log, không dùng số thật.
- `target/car-rental-keycloak-0.0.1-SNAPSHOT-tests.jar`: chỉ cho container test. **Không cài JAR này
  vào Keycloak local hay bất kỳ môi trường triển khai nào.** Nó không có trong compose.

Các authenticator `car-rental-employee-password` và `car-rental-employee-sms` dành cho hai bước
mật khẩu rồi SMS của nhân viên (BR-809). Khi cấu hình flow, cả hai đều phải là `REQUIRED` và dùng
login theme `car-rental`. Mật khẩu được Keycloak xác minh; bước SMS chỉ chấp nhận dấu xác minh
gắn với đúng realm, phiên, client, tab, người dùng và số điện thoại.

Realm ở `infra/keycloak/realm-export.json` gắn hai bước này cho `admin-web` và luồng OTP khách cho
`customer-web`/`mobile`. Cả ba client chỉ dùng Authorization Code + PKCE S256. Scope của client khách
chỉ phát vai `CUSTOMER`, kể cả tài khoản đồng thời là nhân viên. Compose mount đúng provider lõi,
adapter SMS local và file realm. Build và điền `.env` theo [hướng dẫn chạy local](../README.md#2-khởi-động-hạ-tầng)
trước khi khởi động. Có thể kiểm tra riêng lớp xử lý mà không cần Docker:

```sh
./mvnw '-Dtest=EmployeePasswordAuthenticatorTest,EmployeeSmsAuthenticatorTest,EmployeeAuthenticatorFactoryTest' test
```

`OtpProvider` được gọi từ session Keycloak.
Giao dịch con commit số lần gửi/nhập sai và trạng thái mã độc lập với giao dịch đăng nhập ngoài.
Số/IP và phạm vi phiên lưu dưới dạng HMAC; OTP lưu HMAC với challenge riêng, không lưu bản rõ.

Authenticator `car-rental-customer-otp` nhận số di động Việt Nam, xác minh SMS rồi yêu cầu khách mới
đồng ý điều khoản trước khi tạo người dùng Keycloak. Tài khoản nhân viên được dùng lại khi người đó
đăng ký thuê xe, chỉ bổ sung vai `CUSTOMER`. Hai thuộc tính `terms_version` và `consented_at` giữ
phiên bản/thời điểm đồng ý đầu tiên; khách cũ không bị yêu cầu đồng ý lại khi đổi phiên bản cấu hình.
Việc ghi đồng ý dùng transaction riêng và khoá theo realm/số trên chính CSDL Keycloak.

Văn bản ở `src/main/resources/terms/local-test-terms.txt` là **bản thử, chỉ dùng local**.
Luồng này từ chối chạy ngoài `start-dev`. `CAR_RENTAL_TERMS_VERSION` bắt buộc, ví dụ
`local-test-v1`; thay phiên bản cần restart, thay nội dung cần build/nạp lại provider.

Kiểm lớp xử lý OTP/đồng ý điều khoản và thao tác người dùng, không cần Docker:

```sh
./mvnw '-Dtest=CustomerOtpAuthenticatorTest,CustomerAccountRegistrationTest,LocalTermsDocumentTest' test
```

## Kiểm đăng nhập với realm thật

```sh
./mvnw '-Dit.test=KeycloakLoginIT,ProviderPackagingIT' verify
```

Lệnh package lại provider trước khi dựng Keycloak/PostgreSQL tạm. Trình duyệt HTTP của test gửi form,
giữ cookie và đổi code bằng PKCE; callback Postman chỉ được chặn/đọc trong bộ nhớ, không gửi request
tới Postman. Container này **không cài JAR probe**. Mật khẩu người dùng mẫu và secret client kỹ thuật
được sinh riêng trong test, không đọc `.env`.

Realm local khai báo bốn nhân viên thử `+84910000001` → `+84910000004`, tương ứng ADMIN, BRANCH_STAFF,
hai BRANCH_MANAGER. Mật khẩu import lấy từ các biến `CAR_RENTAL_LOCAL_ADMIN_PASSWORD`,
`CAR_RENTAL_LOCAL_STAFF_PASSWORD`, `CAR_RENTAL_LOCAL_MANAGER_A_PASSWORD`,
`CAR_RENTAL_LOCAL_MANAGER_B_PASSWORD`; đều là mật khẩu tạm phải đổi sau mật khẩu + SMS.
Client `identity-reader` lấy secret từ `CAR_RENTAL_KEYCLOAK_READER_SECRET`, chỉ có `view-users` và
`query-users` được gán trực tiếp; `view-users` của Keycloak bao gồm `query-groups`, nên token có thêm
quyền đọc đó nhưng không có `manage-users`. Chỉ dùng các số/tài khoản này làm dữ liệu thử, không gửi SMS thật.

Required action `UPDATE_PASSWORD` dùng provider có guard khi cấu hình
`car-rental.employee-mfa-required=true` trong realm. Phải có bằng chứng của cả mật khẩu lẫn SMS trong
đúng phiên nhân viên mới được mở/gửi form đổi mật khẩu. Realm `master` không bật guard này nên giữ
hành vi chuẩn. Không đổi ID của action: Keycloak dùng chính ID đó để xoá yêu cầu đổi mật khẩu sau
khi hoàn tất. Client account/account-console/admin-cli của realm local bị tắt; quản lý nhân viên
dùng `security-admin-console` qua luồng nhân viên.

## Đăng nhập local bằng Postman

Import [collection Identity](../postman/task-08a-identity.postman_collection.json), dùng thư mục
`Part 2 - Keycloak login`. Biến collection `keycloakBaseUrl` mặc định `http://localhost:8080`;
không đổi `baseUrl` của backend trong environment chung.

Chạy request 01 (discovery, HTTP 200) và 02 (thiếu token, HTTP 401). Với request 03/04:

1. Mở **Authorization**, dùng **OAuth 2.0 → Authorization Code (With PKCE)**, thuật toán **S256**.
   Collection có sẵn URL/client/scope; Client Secret để trống. Bỏ chọn **Authorize using browser**,
   giữ Callback URL `https://oauth.pstmn.io/v1/callback` đúng với realm đã import.
2. Chọn **Get New Access Token**, đăng nhập trong cửa sổ Postman. Khách dùng số thử, OTP và đồng ý
   điều khoản thử ở lần đầu. Nhân viên dùng mật khẩu rồi OTP; mật khẩu tạm phải đổi lần đầu.
3. Đọc OTP giả ở terminal riêng (chỉ dùng dữ liệu thử):

   ```sh
   cd /Users/diepchu/project/car-rental
   docker compose logs --since 1m -f keycloak
   ```

   Tìm `LOCAL TEST SMS ONLY challenge=... otp=...`, lấy mã của lần đăng nhập vừa mở. Không chia sẻ
   OTP hay log này. Dừng xem log bằng Ctrl+C không làm dừng Keycloak.
4. Chọn **Use Token**, rồi **Send**: HTTP 200 và các `pm.test` xanh. Request 03 kiểm số chuẩn E.164
   và bằng chứng đồng ý; request 04 mặc định kiểm nhân viên STAFF `+84910000002`.

Không bật chia sẻ/đồng bộ token trong Postman, không lưu token vào file hoặc Git. Runner chỉ kiểm
các request sau khi đã lấy token còn hạn; không tự nhập mật khẩu/OTP. Token hết hạn thì lấy lại
bằng cửa sổ OAuth. Đăng nhập lại không cần xoá người dùng nhưng vẫn chịu hạn mức BR-129.
Nếu đổi nhân viên để kiểm, đổi biến `employeePhone` và lấy token mới đúng người; đây không phải
biến tự điền vào form. Chưa lấy token hoặc quên **Use Token** thì userinfo trả 401.

## Cấu hình provider

Mọi biến dưới đây bắt buộc, không có mặc định trong thuật toán. Giá trị khởi điểm theo BR-129:

| Biến | Giá trị khởi điểm |
|---|---|
| `CAR_RENTAL_OTP_DIGITS` | `6` |
| `CAR_RENTAL_OTP_LIFETIME` | `PT5M` |
| `CAR_RENTAL_OTP_MAX_FAILURES` | `5` |
| `CAR_RENTAL_OTP_RESEND_DELAY` | `PT60S` |
| `CAR_RENTAL_OTP_PHONE_HOURLY_LIMIT` | `5` |
| `CAR_RENTAL_OTP_PHONE_DAILY_LIMIT` | `10` |
| `CAR_RENTAL_OTP_IP_SEND_HOURLY_LIMIT` | `20` |
| `CAR_RENTAL_OTP_IP_FAILURE_HOURLY_LIMIT` | `100` |
| `CAR_RENTAL_OTP_HMAC_SECRET` | Secret riêng, base64 tối thiểu 32 byte; không có giá trị mẫu để triển khai |

Giữ nguyên secret qua restart để không làm thay đổi khoá hạn mức. Đổi cấu hình cần restart Keycloak.
Không lấy secret công khai trong `src/test/resources/otp-test.properties` để dùng ngoài container test.
Không in phone/IP, token hoặc mật khẩu vào log. Adapter local chỉ in `challenge` và `otp`.
