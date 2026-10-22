# API tài khoản — client và admin

## Phạm vi

Đọc tài khoản của chính người đang đăng nhập và ADMIN gán chi nhánh theo BR-120/803/807/809 và ADR-0006.
Không có API tìm tài khoản theo số điện thoại hoặc đọc tài khoản khác bằng mã.

| Method | Path | Quyền | Thành công |
|---|---|---|---|
| GET | `/api/v1/client/accounts/me` | JWT hợp lệ có vai `CUSTOMER` | 200 |
| GET | `/api/v1/admin/accounts/me` | JWT hợp lệ có vai nhân viên, `azp=admin-web` | 200 |
| PUT | `/api/v1/admin/accounts/{accountCode}/branch` | JWT nhân viên hợp lệ; use case yêu cầu `ADMIN` | 200 |

Nhân viên chưa được gán chi nhánh vẫn đọc được `/admin/accounts/me` để lấy mã `TK-<6>`
cung cấp cho ADMIN. Điều này **không** cấp quyền thao tác xe hoặc chi nhánh (BR-803/810).

## GET /accounts/me — request

Không có body hoặc tham số lựa chọn tài khoản. Danh tính chỉ lấy từ JWT đã xác minh;
truyền mã, số điện thoại, vai hay chi nhánh trong query/header không thay được người gọi.

```http
Authorization: Bearer <access-token>
Accept: application/json
X-Client-Platform: web
X-Client-Version: 0.0.1
```

Lấy token bằng Authorization Code + PKCE: khách dùng `customer-web` hoặc `mobile`,
nhân viên dùng `admin-web` với mật khẩu và OTP. Không truyền token qua query, form hoặc cookie.
Áp dụng [X-Request-Id](http-headers.md); Security thêm `Cache-Control` có `no-store`.

## GET /accounts/me — response

Ví dụ minh hoạ với dữ liệu giả của khách:

```json
{
  "success": true,
  "data": {
    "code": "TK-SELF01",
    "phoneNumber": "+84912345678",
    "roles": ["CUSTOMER"],
    "branchCode": null,
    "termsVersion": "local-test-v1",
    "consentedAt": "2026-10-12T03:00:00Z"
  },
  "error": null
}
```

- `code`: mã nghiệp vụ tài khoản, không phải ID số hoặc subject Keycloak.
- `phoneNumber`: số di động Việt Nam ở dạng E.164 của chính tài khoản.
- `roles`: tập vai đã xác thực của **request hiện tại**; thứ tự phần tử không có ý nghĩa.
  Realm giới hạn vai theo client: token khách không dùng để thực hiện quyền nhân viên,
  dù cùng một tài khoản có cả CUSTOMER và vai nhân viên (BR-809).
- `branchCode`: mã chi nhánh theo phạm vi caller, hoặc `null` khi chưa gán/không có phạm vi chi nhánh.
  Backend tra qua `branch.api`, không trả `branchId`, không lưu thêm mã vào bảng identity.
- `termsVersion`, `consentedAt`: bằng chứng đầu tiên đã lưu; không tính lại theo giờ gọi hoặc phiên bản mới.
  Nhân viên chưa đăng ký thuê có thể thiếu cả cặp (`null`), không tự tạo consent thay nhân viên.

Account được đồng bộ ở biên xác thực trước khi vào controller; use case đọc `/me` chỉ đọc CSDL.
Không có token, mật khẩu, OTP hay subject Keycloak trong response. Không lưu response thật vào repo.

## GET /accounts/me — lỗi

| HTTP | Mã | Khi nào |
|---|---|---|
| 401 | `UNAUTHENTICATED` | Thiếu/token sai, hết hạn hoặc claims danh tính không hợp lệ; có `WWW-Authenticate: Bearer` |
| 403 | `ACCESS_DENIED` | Token hợp lệ nhưng không có quyền namespace |
| 403 | `CONSENT_REQUIRED` | Token khách thiếu bằng chứng đồng ý bắt buộc |
| 403 | `ACCOUNT_IDENTITY_MISMATCH` | Danh tính xác thực không khớp tài khoản đã lưu |
| 403 | `STAFF_ROLE_MISMATCH` | Vai nhân viên hiện tại không khớp vai của bản gán chi nhánh |
| 404 | `ACCOUNT_NOT_FOUND` | Không còn bản ghi tài khoản hiện tại khi đọc sau bước đồng bộ |
| 500 | `INTERNAL_ERROR` | Lỗi lưu trữ hoặc tham chiếu chi nhánh không nhất quán; chi tiết chỉ ở log máy chủ |

Response lỗi giữ envelope `{success:false,data:null,error:{code,message}}`;
không trả tên lớp, số dòng, SQL hoặc thông báo parser JWT.

## PUT /api/v1/admin/accounts/{accountCode}/branch

BR-803/807/809: chỉ ADMIN gán hoặc thay chi nhánh cho một tài khoản nhân viên đã tồn tại.
Nhân viên phải gọi `/admin/accounts/me` ít nhất một lần để đồng bộ account và lấy mã `TK-<6>`.
Không nhập ID số trên URL. `accountCode` và `branchCode` phải đúng định dạng, không tự cắt trắng/đổi hoa.

Header như GET, thêm `Content-Type: application/json`. Body:

```json
{"branchCode":"CN-LOCAL1"}
```

Request không có vai, subject Keycloak, ID số hoặc người thực hiện.
Use case lấy người thực hiện từ CallerDirectory; tra vai **hiện tại** của tài khoản đích qua
client Keycloak chỉ đọc, không lấy vai từ bản gán cũ hay token của ADMIN.
Chỉ gán cho `BRANCH_STAFF`, `BRANCH_MANAGER`, `DELIVERY_STAFF`, `DRIVER`.
`ADMIN`, `ACCOUNTANT`, `OPERATIONS_MANAGER` không có phạm vi một chi nhánh.

Ghi và đọc lại trong cùng transaction. Mỗi tài khoản có một bản gán: gọi lại cập nhật bản gán đó,
không tạo thêm bản ghi; `assignedAt` có thể đổi theo lần gọi. Không cấp/đổi vai Keycloak.
Chi nhánh đã có quản lý khác trả 409, không giành chỗ quản lý cũ. UNIQUE ở PostgreSQL phân xử cả khi
hai ADMIN gán đồng thời; lỗi làm rollback, không để lại bản gán dở dang.

Response 200 minh hoạ:

```json
{
  "success": true,
  "data": {
    "accountCode": "TK-STAFF1",
    "branchCode": "CN-LOCAL1",
    "staffRole": "BRANCH_STAFF",
    "assignedAt": "2026-10-16T03:00:00Z"
  },
  "error": null
}
```

| HTTP | Mã | Khi nào |
|---|---|---|
| 400 | `INVALID_REQUEST` | Thiếu/sai body hoặc mã nghiệp vụ |
| 401 | `UNAUTHENTICATED` | Thiếu hoặc sai token |
| 403 | `ACCESS_DENIED` | Người thực hiện không phải ADMIN |
| 404 | `ACCOUNT_NOT_FOUND` | Chưa có account nghiệp vụ hoặc không tìm thấy user đích trong Keycloak |
| 404 | `BRANCH_NOT_FOUND` | Mã chi nhánh không tồn tại |
| 409 | `BRANCH_MANAGER_ALREADY_ASSIGNED` | Chi nhánh đã có quản lý khác |
| 422 | `STAFF_ROLE_NOT_BRANCH_SCOPED` | Tài khoản đích không có vai nhân viên thuộc một chi nhánh |
| 500 | `INTERNAL_ERROR` | Lỗi nguồn danh tính/lưu trữ hoặc dữ liệu đọc lại không nhất quán |

Các lỗi ở biên xác thực như lệch danh tính/consent/bản gán giữ nguyên mã đã mô tả ở phần GET.
Sau khi gán, request tiếp theo của nhân viên đọc lại phạm vi từ CSDL, không cần nhét chi nhánh vào token.

## Kiểm bằng Postman

Import `postman/task-08a-identity.postman_collection.json`, chọn environment `Car Rental - Local`,
chạy thư mục `Part 3 - Current account`. Chuẩn bị token tương tác trước khi dùng Runner;
collection không tự nhập mật khẩu/OTP. Không bật Share/Sync Token hoặc export token vào Git.
Request thành công lưu mã tài khoản vào biến collection để dùng ở các bước gán chi nhánh sau,
không bắt copy mã từ response. Các request chỉ đọc, chạy lại không cần dọn dữ liệu.

Thư mục `Part 3 - Staff branch assignment` lấy lại mã nhân viên qua `/me`, tạo một chi nhánh thử bằng
token ADMIN rồi gán. Mỗi lần chạy tạo chi nhánh thử mới và thay bản gán của **nhân viên local thử**;
không dùng với tài khoản/dữ liệu thật. Script truyền mã tự động; không tự sao chép token giữa các vai.
Các ca thiếu token, STAFF tự gán và ADMIN gửi body sai phải bị từ chối trước khi request gán hợp lệ chạy.
