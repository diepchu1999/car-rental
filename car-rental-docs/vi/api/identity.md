# API tài khoản và giấy tờ — client và admin

## Phạm vi

Đọc tài khoản của chính người đang đăng nhập và ADMIN gán chi nhánh theo BR-120/803/807/809 và ADR-0006.
Không có API tìm tài khoản theo số điện thoại hoặc đọc tài khoản khác bằng mã.
Khách có thể xin URL tải từng mặt ảnh, nộp hồ sơ và đọc trạng thái giấy tờ của chính mình theo BR-130.

| Method | Path | Quyền | Thành công |
|---|---|---|---|
| GET | `/api/v1/client/accounts/me` | JWT hợp lệ có vai `CUSTOMER` | 200 |
| GET | `/api/v1/admin/accounts/me` | JWT hợp lệ có vai nhân viên, `azp=admin-web` | 200 |
| PUT | `/api/v1/admin/accounts/{accountCode}/branch` | JWT nhân viên hợp lệ; use case yêu cầu `ADMIN` | 200 |
| POST | `/api/v1/client/document-uploads` | JWT khách hợp lệ có vai `CUSTOMER`, đã đồng ý điều khoản | 201 |
| POST | `/api/v1/client/documents` | JWT khách hợp lệ có vai `CUSTOMER`, đã đồng ý điều khoản | 201 |
| GET | `/api/v1/client/documents` | JWT khách hợp lệ có vai `CUSTOMER`, đã đồng ý điều khoản | 200 |
| GET | `/api/v1/client/documents/{documentCode}` | Như trên, chỉ hồ sơ của chính người gọi | 200 |

Nhân viên chưa được gán chi nhánh vẫn đọc được `/admin/accounts/me` để lấy mã `TK-<6>`
cung cấp cho ADMIN. Điều này **không** cấp quyền thao tác xe hoặc chi nhánh (BR-803/810).

`DOCUMENT_VERIFIER` là vai nhân viên cấp công ty (BR-130/803), đăng nhập `admin-web` bằng
mật khẩu + OTP. `/admin/accounts/me` trả vai này với `branchCode: null`; không cần gán chi nhánh.
Vai này không có quyền tạo/đọc chi nhánh, tạo/đọc/duyệt xe, đổi giá hoặc gán nhân viên vào chi nhánh.
Token từ client khách chỉ có `CUSTOMER`, không mang quyền xác minh kể cả cùng một tài khoản.

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

## POST /api/v1/client/document-uploads

BR-120/130: cấp một liên kết PUT có hạn cho **một mặt ảnh** của chính khách đang đăng nhập.
Header như GET, thêm `Content-Type: application/json`. Body chỉ có hai trường bắt buộc:

```json
{"documentType":"IDENTITY_CARD","side":"FRONT"}
```

- `documentType`: `IDENTITY_CARD` hoặc `DRIVER_LICENSE`.
- `side`: `FRONT` hoặc `BACK`.
- Enum phân biệt hoa/thường, không tự cắt trắng. Trường lạ bị từ chối; không nhận `accountCode`,
  `objectKey`, URL hay thời hạn do client đặt. Chủ luôn lấy từ phiên đã xác minh.

Response 201 minh hoạ (URL dưới đây không sử dụng được):

```json
{
  "success": true,
  "data": {
    "code": "AN-LOCAL1",
    "documentType": "IDENTITY_CARD",
    "side": "FRONT",
    "uploadUrl": "http://localhost:9000/example-bucket/incoming/example?example-signature=not-valid",
    "expiresAt": "2026-10-24T03:05:00Z"
  },
  "error": null
}
```

`code` là mã lượt tải `AN-<6>`, không phải mã hồ sơ `GT-<6>`. `uploadUrl` dùng HTTP PUT;
hạn lấy từ cấu hình storage (5 phút theo BR-130), lưu cố định khi tạo, không gia hạn khi đọc.
URL chỉ trả sau khi transaction lưu lượt tải đã commit; CSDL giữ object key, **không lưu URL ký**.
Response có `Cache-Control: no-store` và `X-Request-Id`. Không có `Location` vì chưa có GET theo mã AN.
Không chia sẻ, log hoặc lưu URL thật vào repo; người có URL có thể PUT trong thời hạn của nó.

Gọi lại cấp lượt tải mới và mã AN mới. Endpoint này **chưa nộp hồ sơ GT**, không tự xác minh giấy tờ,
không chứng minh nội dung ảnh hợp lệ. Kiểm JPEG/PNG/HEIC, byte/pixel thực hiện khi tiếp nhận và chốt ảnh,
không dựa vào loại file do khách tự khai trong request xin URL.

| HTTP | Mã | Khi nào |
|---|---|---|
| 400 | `INVALID_REQUEST` | Thiếu/null/sai enum, JSON sai hoặc trường ngoài hợp đồng |
| 401 | `UNAUTHENTICATED` | Thiếu/sai token |
| 403 | `ACCESS_DENIED` | Không có quyền namespace khách, kể cả token nhân viên ADMIN |
| 403 | `CONSENT_REQUIRED` | Không có bằng chứng đồng ý điều khoản |
| 404 | `ACCOUNT_NOT_FOUND` | Tài khoản không còn tồn tại sau bước đồng bộ |
| 500 | `INTERNAL_ERROR` | Ký URL, ghi/reload/commit thất bại hoặc không cấp được AN sau 5 lần trùng mã |

Lỗi ở biên xác thực giữ nguyên như GET `/accounts/me`. JSON lỗi không chứa URL, object key hay SQL.

Kiểm bằng `postman/task-08b-documents.postman_collection.json`, environment `Car Rental - Local`:
chuẩn bị token `customer-web` tại Authorization của thư mục `Part 2 - Document upload URLs (BR-130)`,
rồi chạy cả thư mục. Các request kiểm bốn cặp loại/mặt, body sai và thiếu token; chạy lại tạo lượt tải mới,
không cần dọn dữ liệu. Bộ này chỉ xin URL, **không upload ảnh hay nộp hồ sơ**. Không bật Share/Sync Token.

## POST /api/v1/client/documents

BR-120/130: nộp một phiên bản CCCD hoặc GPLX của chính người gọi. Đầu tiên cấp hai lượt tải bằng
`POST /document-uploads` với đúng loại và hai mặt FRONT/BACK, rồi PUT bytes ảnh vào từng URL ký.
Request PUT đi trực tiếp tới MinIO, dùng **No Auth**, không gửi bearer token của backend.
Sau khi PUT thành công, gửi hai mã AN trong body JSON, không gửi URL hoặc object key.

Ví dụ CCCD với dữ liệu hoàn toàn giả:

```json
{
  "documentType": "IDENTITY_CARD",
  "number": "LOCAL-TEST-IDENTITY-CARD",
  "fullName": "LOCAL FAKE DOCUMENT",
  "birthDate": "1990-01-01",
  "expiresOn": "2035-01-01",
  "frontUploadCode": "AN-FRONT1",
  "backUploadCode": "AN-BACK01"
}
```

Ví dụ GPLX:

```json
{
  "documentType": "DRIVER_LICENSE",
  "number": "LOCAL-TEST-DRIVER-LICENSE",
  "licenseClass": "B",
  "automaticOnly": false,
  "expiresOn": "2035-01-01",
  "frontUploadCode": "AN-FRONT2",
  "backUploadCode": "AN-BACK02"
}
```

- Các trường trong mỗi ví dụ đều bắt buộc; ngày dùng ISO `YYYY-MM-DD`. Hai AN phải khác nhau.
- CCCD không nhận `licenseClass`/`automaticOnly`; GPLX không nhận `fullName`/`birthDate`.
  Trường không áp dụng phải vắng hoặc null. `automaticOnly=false` khác thiếu/null.
- Trường ngoài hợp đồng bị từ chối, gồm chủ, ID số, trạng thái, người duyệt, key và URL.
  Danh tính luôn từ JWT; cả hai AN phải đúng chủ, loại và mặt trước khi đọc/chốt bất kỳ ảnh nào.
- Số được bỏ khoảng trắng hai đầu rồi băm/mã hoá; server không OCR hoặc xác nhận giấy tờ thật lúc nộp.
  Ảnh được kiểm nội dung thật JPEG/PNG/HEIC, giới hạn byte/pixel theo cấu hình BR-130.
- URL PUT hết hạn không phải hạn nộp hồ sơ. Ảnh đã tải hợp lệ vẫn nộp được sau khi URL hết hạn.
- Ảnh đã kiểm được lưu dưới key sealed mới. PUT lại URL cũ không sửa ảnh gắn với hồ sơ đã nộp.
- Bản mới ở PENDING và thay bản PENDING trước bằng SUPERSEDED; không xoá lịch sử hoặc ảnh.
  Bản VERIFIED đang dùng giữ nguyên cho tới khi bản mới được duyệt hoặc bản đó hết hạn (BR-130).
- Dùng lại AN đã gắn vào một hồ sơ trả 409. Muốn nộp lại phải cấp AN và tải ảnh mới.
  Trùng mã GT nội bộ được thử lại tối đa 5 lần, không chốt ảnh lại; lỗi khác không tự retry.
- Ghi hồ sơ/hai ảnh/head và reload cùng một transaction PostgreSQL. Storage không có transaction
  chung với PostgreSQL: lỗi nộp có thể để object sealed chưa được tham chiếu; việc dọn chưa cung cấp.

Response 201 sau commit:

```json
{
  "success": true,
  "data": {
    "code": "GT-LOCAL1",
    "documentType": "IDENTITY_CARD",
    "status": "PENDING",
    "expiresOn": "2035-01-01",
    "submittedAt": "2026-10-27T03:00:00Z"
  },
  "error": null
}
```

Response có `Cache-Control: no-store` và `X-Request-Id`; không có ID số, số giấy tờ, họ tên/ngày sinh,
bản mã/hash, key hay URL ảnh. Dùng `data.code` để đọc trạng thái qua GET theo GT.
`PENDING` không có nghĩa đủ điều kiện thuê. Xác minh và xét hiệu lực/hạng GPLX là thao tác riêng.

| HTTP | Mã | Khi nào |
|---|---|---|
| 400 | `INVALID_REQUEST` | Thiếu/null/sai trường, JSON sai, hai mặt cùng AN, AN sai loại/mặt, hoặc trường ngoài hợp đồng |
| 401 | `UNAUTHENTICATED` | Thiếu/sai token |
| 403 | `ACCESS_DENIED` | Không có quyền namespace khách, kể cả token ADMIN của admin-web |
| 403 | `CONSENT_REQUIRED` | Chưa có bằng chứng đồng ý điều khoản |
| 404 | `ACCOUNT_NOT_FOUND` | Tài khoản không còn sau bước đồng bộ |
| 404 | `DOCUMENT_UPLOAD_NOT_FOUND` | Một trong hai AN không tồn tại hoặc không thuộc người gọi |
| 409 | `DOCUMENT_UPLOAD_ALREADY_USED` | AN đã gắn vào một lần nộp, kể cả tranh chấp đồng thời |
| 422 | `DOCUMENT_UPLOAD_NOT_READY` | Chưa có object của lượt tải trong kho |
| 422 | `DOCUMENT_IMAGE_TOO_LARGE` | Ảnh vượt giới hạn byte; local mặc định 5.000.000 byte |
| 422 | `DOCUMENT_IMAGE_INVALID` | Nội dung sai/hỏng hoặc vượt giới hạn pixel; local mặc định 40 triệu pixel |
| 500 | `INTERNAL_ERROR` | Storage/mật mã/SQL/reload lỗi hoặc không cấp được GT sau 5 lần trùng |

Lỗi xác thực khác giữ như `/accounts/me`; không trả dữ liệu giấy tờ hoặc chi tiết nội bộ trong lỗi.

### Postman: tải ảnh và nộp

Import lại `postman/task-08b-documents.postman_collection.json`, dùng environment `Car Rental - Local`.
Chọn thư mục **Part 2 - Submit documents (BR-130)**, lấy token customer-web rồi Use Token.
Đặt Postman Working directory là thư mục gốc repo theo [hướng dẫn fixture](../../../postman/fixtures/documents/README.md).
Chạy **cả thư mục bằng Collection Runner, 1 iteration**: 27 request tự truyền AN và URL ký,
nộp CCCD/GPLX, rồi thử dùng lại AN, ảnh thiếu/sai/6 MB và đầu vào sai.
Các HTTP 400/401/409/422 là kết quả mong đợi; xem `pm.test`, không chỉ nhìn HTTP màu đỏ.
URL ký chỉ nằm trong `pm.variables` của lượt Runner; không lưu URL vào collection/environment.
Không chạy rời request PUT/nộp; cần chạy lại thì chạy từ request 01 để cấp URL còn hạn và AN mới.
Chỉ hai mã GT kết quả được lưu vào collection variables `submittedIdentityCardCode`, `submittedDriverLicenseCode`.

## GET /api/v1/client/documents — trạng thái theo loại giấy tờ

BR-120/130, security-guideline §2/§3: CUSTOMER chỉ đọc của chính mình. Header như `/accounts/me`.
Không có body hoặc tham số lựa chọn chủ. `accountCode`/`role` do client thêm vào query/header không
thay đổi danh tính. Quyền được kiểm lại ở application service; transaction chỉ đọc.

Response 200 gồm danh sách tối đa hai mục, một mục cho mỗi loại đã nộp, sắp theo tên loại tăng dần
(`DRIVER_LICENSE`, `IDENTITY_CARD`). Chưa nộp trả `data: []`, không phải lỗi 404.

```json
{
  "success": true,
  "data": [
    {
      "documentType": "IDENTITY_CARD",
      "latestSubmission": {
        "code": "GT-LOCAL1",
        "documentType": "IDENTITY_CARD",
        "status": "PENDING",
        "expiresOn": "2035-01-01",
        "submittedAt": "2026-10-27T03:00:00Z",
        "reviewedAt": null,
        "rejectionCode": null,
        "rejectionNote": null
      },
      "activeVerifiedSubmission": null
    }
  ],
  "error": null
}
```

- `latestSubmission`: bản nộp mới nhất để theo dõi tiến trình.
- `activeVerifiedSubmission`: cùng cấu trúc metadata, nhưng là bản VERIFIED được chọn tại head;
  null nếu chưa có. Latest PENDING/REJECTED không thay thế active đã được xác minh.
- Active hết hạn vẫn xuất hiện với `expiresOn` thật; đây **không phải** kết luận đủ điều kiện thuê.
  Không tự quay về một VERIFIED lịch sử khác. Cổng điều kiện thuê kiểm hạn/hạng riêng theo BR-130.
- Hai con trỏ được đọc trong cùng một câu SQL, cùng snapshot. Không đọc lại ảnh hoặc ký URL.

## GET /api/v1/client/documents/{documentCode} — trạng thái một phiên bản

`documentCode` phải đúng `GT-` cộng sáu chữ hoa ASCII/chữ số, không tự cắt trắng hoặc đổi chữ hoa.
Chỉ đọc đúng chủ, kể cả bản lịch sử SUPERSEDED. Không có tham số accountCode.
Response 200 dùng envelope chuẩn; `data` có đúng tám trường như `latestSubmission` ở trên.

| Trường | Ý nghĩa |
|---|---|
| `code`, `documentType` | Mã phiên bản và loại IDENTITY_CARD/DRIVER_LICENSE |
| `status` | PENDING, VERIFIED, REJECTED hoặc SUPERSEDED |
| `expiresOn`, `submittedAt` | Ngày hết hạn đã khai và thời điểm nộp đã lưu |
| `reviewedAt` | Mốc duyệt/từ chối; null với PENDING/SUPERSEDED |
| `rejectionCode` | Một trong sáu mã BR-130 khi REJECTED; ngoài ra null |
| `rejectionNote` | Ghi chú từ chối nếu có; ngoài ra null |

Sáu mã lý do: `ILLEGIBLE`, `MISSING_SIDE`, `DATA_MISMATCH`, `EXPIRED`, `HOLDER_MISMATCH`,
`SUSPECTED_FORGERY`. Ghi chú là văn bản thuần, frontend phải hiển thị như text, không render thành HTML.

Cả hai GET trả `Cache-Control: no-store` và `X-Request-Id`. Không trả ID số, số giấy tờ, tên/ngày sinh,
reviewer, bản mã/hash, key ảnh hoặc URL. Đây là khách đọc metadata của chính mình, không tạo audit
mở giấy tờ của người khác. API admin xem ảnh kèm audit là thao tác riêng.

| HTTP | Mã | Khi nào |
|---|---|---|
| 400 | `INVALID_REQUEST` | Mã trên path không đúng định dạng GT |
| 401 | `UNAUTHENTICATED` | Thiếu/sai/hết hạn token |
| 403 | `ACCESS_DENIED` | Không có quyền CUSTOMER, gồm token ADMIN qua admin-web |
| 403 | `CONSENT_REQUIRED` | Không có bằng chứng điều khoản hợp lệ |
| 404 | `ACCOUNT_NOT_FOUND` | Account không còn sau bước xác thực |
| 404 | `DOCUMENT_NOT_FOUND` | GT không tồn tại hoặc thuộc người khác; cùng mã và thông điệp |
| 500 | `INTERNAL_ERROR` | Lỗi lưu trữ hoặc kết quả tra cứu không nhất quán; không trả chi tiết nội bộ |

Lỗi danh tính khác giữ như `/accounts/me`. `DOCUMENT_NOT_FOUND` chỉ dùng cho GET một phiên bản;
GET danh sách chưa có hồ sơ vẫn 200 với mảng rỗng.

### Postman: đọc trạng thái và chống đọc chéo

Import lại cùng collection Task 8b, giữ environment `Car Rental - Local`, restart backend.
Mỗi folder có OAuth PKCE riêng; chọn đúng token và Use Token ở **Authorization của folder**:

1. **Part 2 - Read document status (BR-130)** — 8 request, dùng CUSTOMER đã nộp cả CCCD/GPLX.
   Nếu chưa có đủ hai loại, chạy folder Submit documents trước với cùng khách. Chạy cả folder bằng
   Runner 1 iteration. Mã chủ/GT được lưu tự động; không giữ ghi chú hoặc URL trong biến.
2. **Part 2 - Reject cross-account document reads (BR-130)** — 3 request, đăng nhập một CUSTOMER
   **khác** bằng PKCE (Clear cookies nếu Keycloak tự dùng lại phiên). Request 01 kiểm đúng khác chủ;
   đọc GT của chủ trước trả 404; thêm accountCode vào query không đổi danh sách của người gọi.
3. **Part 2 - Reject staff document status access (BR-130)** — 3 request, token ADMIN qua admin-web.
   `/admin/accounts/me` thành công nhưng cả hai GET client đều 403.

Chạy theo thứ tự ba folder, tất cả `pm.test` phải xanh, kể cả HTTP 400/401/403/404 có chủ đích.
Các folder đọc không sửa dữ liệu, chạy lại được nhiều lần. Không export token hoặc response giấy tờ.
