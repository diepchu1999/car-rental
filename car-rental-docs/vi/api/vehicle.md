# API xe — admin

## Phạm vi

- BR-001: lưu loại sở hữu, bất biến sau khi tạo; API GĐ1 chỉ tạo `COMPANY`.
- BR-003: tra cứu chi nhánh đã tồn tại qua `branch.api.BranchDirectory` trước khi tạo xe.
- BR-005: lưu ngày hết hạn; kiểm có đủ và còn hạn tại bước duyệt.
- BR-010 và status-flow §4: `DRAFT → PENDING_APPROVAL → ACTIVE`.
- BR-410: `PETROL`, `DIESEL`, `ELECTRIC`, `HYBRID`.
- BR-018: bắt buộc số chỗ, hộp số, hãng và dòng xe ngay khi tạo.
- BR-234: giá ngày nguyên VND, lớn hơn 0 và chia hết cho 1.000; bắt buộc khi duyệt, đổi được ở mọi trạng thái.
- Namespace `/admin` cần access token hợp lệ, vai nhân viên và `azp=admin-web` (BR-801/809).
  Gửi `Authorization: Bearer <access-token>`; thiếu/token sai trả `401 UNAUTHENTICATED`,
  sai quyền namespace trả `403 ACCESS_DENIED`, giữ response envelope chung.
- Tạo xe kiểm quyền tại use case theo BR-810: ADMIN ở mọi chi nhánh;
  STAFF/MANAGER chỉ ở chi nhánh mình; STAFF không được nhập giá.
- Gửi duyệt kiểm vai và chi nhánh đã lưu trên xe theo BR-810; không lấy phạm vi từ body/query/header.
- Duyệt chỉ cho ADMIN hoặc MANAGER của chi nhánh có xe; STAFF không được duyệt (BR-810).
- Đọc xe quản trị chỉ cho ADMIN hoặc STAFF/MANAGER của chi nhánh có xe (BR-810).
- Đổi giá chỉ cho ADMIN hoặc MANAGER của chi nhánh có xe; STAFF không được đổi giá (BR-810).
  Mọi kiểm quyền dữ liệu nằm ở use case, cửa namespace không thay thế chúng. Chỉ dùng dữ liệu giả ở local.
- Không xử lý availability, partner onboarding, điều chuyển, sửa giấy tờ hoặc thanh lý.

## Endpoint

Mọi endpoint áp dụng [header truy vết X-Request-Id](http-headers.md); response JSON không đổi.

| Method | Path | Thành công |
|---|---|---|
| POST | `/api/v1/admin/vehicles` | 201, Location trỏ tới xe mới |
| GET | `/api/v1/admin/vehicles/{code}` | 200 |
| POST | `/api/v1/admin/vehicles/{code}/submit-for-approval` | 200, PENDING_APPROVAL |
| POST | `/api/v1/admin/vehicles/{code}/approve` | 200, ACTIVE |
| PUT | `/api/v1/admin/vehicles/{code}/daily-rate` | 200, chi tiết xe với giá mới |

URL luôn dùng mã `XE-<6>`, không dùng khóa chính số.
Tra cứu giữ nguyên mã đầu vào, không cắt khoảng trắng hoặc đổi kiểu chữ.

## Tạo xe

### Quyền tạo — BR-803/810

- `ADMIN` được tạo ở mọi chi nhánh, có hoặc không có giá, không cần được gán chi nhánh.
- `BRANCH_STAFF` và `BRANCH_MANAGER` chỉ tạo ở chi nhánh mình.
  Chưa gán nhận `403 STAFF_BRANCH_REQUIRED`; chọn chi nhánh khác đang tồn tại nhận `403 ACCESS_DENIED`.
- Chỉ `BRANCH_MANAGER` của chi nhánh đó hoặc `ADMIN` được nhập giá khi tạo.
  STAFF gửi kèm giá trong yêu cầu hợp lệ nhận `403 ACCESS_DENIED`, không tạo xe và không bỏ qua giá.
- Vai khác không được tạo, kể cả `ACCOUNTANT`/`OPERATIONS_MANAGER`.
- Kiểm quyền trong use case trước sinh mã/ghi xe. Vai và phạm vi lấy từ danh tính đã xác thực,
  không nhận từ header, query hoặc các trường ngoài hợp đồng trong body.
- Validation cấu trúc command và giá theo BR-234 vẫn thực hiện trước khi gọi use case;
  JSON sai hoặc giá không hợp lệ giữ lỗi đầu vào bên dưới.

Content-Type: `application/json`.

```json
{
  "plateNumber": "51H-123.45",
  "fuelType": "PETROL",
  "seats": 5,
  "transmission": "AUTOMATIC",
  "make": "Toyota",
  "model": "Vios",
  "branchCode": "CN-3TR7WK",
  "inspectionExpiresOn": "2030-12-31",
  "liabilityInsuranceExpiresOn": "2030-12-31",
  "dailyRate": 1000000
}
```

Bảy trường `plateNumber`, `fuelType`, `branchCode`, `seats`, `transmission`, `make`, `model`
bắt buộc, không nhận null. Chuỗi không được rỗng hoặc chỉ chứa khoảng trắng.

| Trường BR-018 | Hợp đồng |
|---|---|
| `seats` | Số nguyên thuộc tập `4`, `5`, `7`, `16` |
| `transmission` | `MANUAL` hoặc `AUTOMATIC` |
| `make` | Hãng xe nhập tự do, giữ nguyên cách viết; không đặt độ dài tối đa |
| `model` | Dòng xe nhập tự do, giữ nguyên cách viết; không đặt độ dài tối đa |

Thay đổi bắt buộc này được duyệt trước phát hành, chỉ local: cập nhật trực tiếp `v1`, không mở `v2`.
Request cũ thiếu bốn trường mới không còn hợp lệ. Không tự suy hãng/dòng hoặc điền giá trị mặc định.

Không tự chuẩn hóa biển số; chưa có quy tắc định dạng biển số được chốt.
Hai ngày theo `YYYY-MM-DD`, có thể bỏ qua hoặc gửi null ở bước tạo bản nháp.
Giấy tờ đã hết hạn cũng không ngăn tạo DRAFT; chúng ngăn bước duyệt.

`dailyRate` không bắt buộc khi tạo: bỏ qua hoặc gửi null thì lưu null, không có giá mặc định.
Nếu cung cấp, giá phải là số nguyên VND > 0, chia hết cho 1.000 và biểu diễn được bằng `BIGINT`.
Giá 0, âm, 1.500, số có phần lẻ khác 0 hoặc vượt giới hạn số nguyên 64 bit trả
422 `VEHICLE_INVALID_DAILY_RATE`, không tự làm tròn hoặc cắt phần lẻ.

Các trường ngoài hợp đồng bị bỏ qua. Gửi `ownershipType`, `status`, `code`, `id`,
`branchId` không làm thay đổi COMPANY/DRAFT hoặc định danh do server quyết định.

Response 201, ví dụ `Location: /api/v1/admin/vehicles/XE-8KQ4M2`:

```json
{
  "success": true,
  "data": {
    "code": "XE-8KQ4M2",
    "plateNumber": "51H-123.45",
    "ownershipType": "COMPANY",
    "fuelType": "PETROL",
    "seats": 5,
    "transmission": "AUTOMATIC",
    "make": "Toyota",
    "model": "Vios",
    "branchCode": "CN-3TR7WK",
    "status": "DRAFT",
    "inspectionExpiresOn": "2030-12-31",
    "liabilityInsuranceExpiresOn": "2030-12-31",
    "dailyRate": 1000000
  },
  "error": null
}
```

Cả năm endpoint trả **`branchCode`**, không có `branchId` và không trả `id` của xe (R12).
Chi tiết xe luôn có `dailyRate`, bằng null khi chưa nhập giá.
Đây là thay đổi trước phát hành, sửa thẳng `v1` theo api-guideline, không tạo `v2`.
Mã chi nhánh được application lấy qua `branch.api`; CSDL vẫn chỉ lưu `branch_id`,
không thêm cột `branch_code`, không suy mã từ ID và không JOIN chéo schema (ADR-0008).
Luồng tạo xe tái sử dụng mã chi nhánh đã tra trước khi chèn; luồng đọc/gửi duyệt/duyệt/đổi giá tra bằng ID đã lưu.
`branchCode` có thể null nếu hồ sơ không có liên kết chi nhánh; luồng tạo COMPANY ở GĐ1 luôn có mã.
Có ID tham chiếu nhưng chi nhánh không tồn tại là lỗi toàn vẹn nội bộ (500 `INTERNAL_ERROR`),
không trả mã giả/null hoặc biến thành lỗi 404 của mã xe. Lỗi sau ghi làm rollback giao dịch.

Service thử tối đa năm mã `XE-<6>` nếu trùng mã. Trùng biển số dừng ngay, không thử lại.
Endpoint chưa hỗ trợ Idempotency-Key; gửi lại cùng biển số sẽ nhận 409.

## Đọc, gửi duyệt và phê duyệt

GET trả cùng hình dạng dữ liệu như POST tạo xe, kể cả giấy tờ null hoặc đã hết hạn.
Gửi duyệt và phê duyệt giữ nguyên cả bốn thuộc tính BR-018 và giá ngày đã nhập.

Hai POST chuyển trạng thái không cần body. Body không được dùng để chọn trạng thái,
ngày duyệt hoặc sửa thông tin giấy tờ.

### Quyền đọc — BR-803/810

`GET /api/v1/admin/vehicles/{code}`:

- `ADMIN` xem mọi xe, không yêu cầu được gán chi nhánh.
- `BRANCH_STAFF`/`BRANCH_MANAGER` chỉ xem xe có chi nhánh khớp với phân công của mình.
  Chưa gán nhận `403 STAFF_BRANCH_REQUIRED` trước khi đọc xe; liên kết khác hoặc null nhận
  `403 ACCESS_DENIED` trước khi tra mã chi nhánh để trả response.
- Các vai khác nhận `403 ACCESS_DENIED` trước khi đọc xe, kể cả ACCOUNTANT/OPERATIONS_MANAGER.
- Người đủ vai và đã có phạm vi được gán gọi mã không tồn tại nhận `404 VEHICLE_NOT_FOUND`.
- Không kiểm lại điều kiện phê duyệt: mọi trạng thái, giấy tờ thiếu/hết hạn và giá null vẫn đọc được
  nếu đúng quyền. Query/header tự khai vai hoặc chi nhánh không thay đổi phạm vi đã xác thực.
- Kiểm tại use case; không áp quyền quản trị này lên các cổng tra cứu nội bộ dành cho search/pricing.

### Quyền gửi duyệt — BR-803/810

`POST /api/v1/admin/vehicles/{code}/submit-for-approval`:

- `ADMIN` gửi xe ở mọi chi nhánh, không yêu cầu được gán chi nhánh.
- `BRANCH_STAFF`/`BRANCH_MANAGER` chỉ gửi xe của chi nhánh mình, kể cả xe có giá đã nhập trước đó.
  Quyền gửi duyệt không đồng nghĩa với quyền nhập giá hoặc phê duyệt.
- Sai vai trả `403 ACCESS_DENIED`, chưa được gán trả `403 STAFF_BRANCH_REQUIRED` trước khi tải xe.
- Đủ vai/phạm vi nhưng mã xe không tồn tại trả `404 VEHICLE_NOT_FOUND`.
- Xe thuộc chi nhánh khác trả `403 ACCESS_DENIED` **trước kiểm trạng thái**, kể cả xe đã gửi duyệt;
  không trả dữ liệu hay lỗi trạng thái của xe đó. Không có liên kết chi nhánh khớp cũng bị từ chối.
- Các chốt quyền ở use case; header/query/body tự khai vai hoặc chi nhánh không thay thế phạm vi
  đã xác thực và chi nhánh đọc từ aggregate.

### Quyền phê duyệt — BR-803/810

`POST /api/v1/admin/vehicles/{code}/approve`:

- `ADMIN` duyệt ở mọi chi nhánh, không cần được gán chi nhánh.
- `BRANCH_MANAGER` chỉ duyệt xe của chi nhánh mình; chưa gán nhận `403 STAFF_BRANCH_REQUIRED`
  trước khi tải xe. Xe khác chi nhánh (hoặc không có liên kết khớp) nhận `403 ACCESS_DENIED`.
- `BRANCH_STAFF` bị từ chối ngay cả với xe cùng chi nhánh; các vai còn lại cũng nhận
  `403 ACCESS_DENIED` trước khi tải xe. Quyền gửi duyệt không đồng nghĩa quyền phê duyệt.
- Kiểm quyền trước trạng thái, giấy tờ và giá; không trả lỗi nội dung hồ sơ của xe ngoài phạm vi.
- Đủ quyền nhưng mã không tồn tại nhận `404 VEHICLE_NOT_FOUND`; đủ quyền không bỏ qua các
  điều kiện nghiệp vụ dưới đây. Vai, chi nhánh, ngày duyệt hoặc trạng thái tự khai không có tác dụng.

### Điều kiện chuyển trạng thái

1. Gửi duyệt chỉ nhận DRAFT và trả PENDING_APPROVAL; chưa kiểm đủ giấy tờ hoặc giá ngày.
2. Duyệt chỉ nhận PENDING_APPROVAL. Cả đăng kiểm và TNDS phải có ngày hết hạn
   **sau ngày duyệt**; ngày hết hạn bằng ngày duyệt cũng bị từ chối.
   Kiểm lỗi theo thứ tự: trạng thái → giấy tờ thiếu/hết hạn → giá ngày thiếu.
   Sau khi giấy tờ hợp lệ, thiếu giá trả 422 `VEHICLE_DAILY_RATE_REQUIRED` (BR-234).
3. Ngày duyệt là ngày server theo `Asia/Ho_Chi_Minh`, không phụ thuộc timezone mặc định của JVM.
   Đây là quy ước kỹ thuật của Task 4 cho triển khai tại Việt Nam.
4. Cập nhật dùng `WHERE code = :code AND status = :expectedStatus`; không ghi đè trạng thái mới hơn.
5. Ghi và đọc lại nằm trong cùng transaction. Lỗi runtime sau ghi làm rollback thao tác đó.

Không có endpoint sửa giấy tờ trong Task 4. Hồ sơ chưa đủ giấy tờ được dùng để chứng minh cổng
duyệt chặn đúng; đừng thêm endpoint sửa chỉ để hoàn thành ví dụ này.

## Đổi giá ngày

`PUT /api/v1/admin/vehicles/{code}/daily-rate`, Content-Type `application/json`:

```json
{
  "dailyRate": 855000
}
```

- BR-803/810: ADMIN đổi ở mọi chi nhánh, không yêu cầu được gán; MANAGER chỉ đổi xe thuộc chi nhánh mình.
  MANAGER chưa gán nhận `403 STAFF_BRANCH_REQUIRED` trước khi tải xe;
  xe khác chi nhánh hoặc không có liên kết khớp nhận `403 ACCESS_DENIED` trước khi ghi.
  STAFF và các vai còn lại nhận `403 ACCESS_DENIED` trước khi tải xe, kể cả cùng chi nhánh.
  Phạm vi lấy từ danh tính đã xác thực và aggregate đã lưu, không nhận từ body/header/query.
- BR-234: không giới hạn trạng thái; DRAFT, PENDING_APPROVAL, ACTIVE, INACTIVE, REJECTED và RETIRED đều đổi được.
- `dailyRate` bắt buộc, không cho xóa giá: thiếu/null hoặc số không hợp lệ trả 422 `VEHICLE_INVALID_DAILY_RATE`.
  JSON không đọc được hoặc giá không chuyển được thành số trả 400 `INVALID_REQUEST`.
- Command kiểm đầu vào trước khi gọi use case. Đầu vào hợp lệ và người gọi đủ quyền nhưng mã xe
  không tồn tại trả 404 `VEHICLE_NOT_FOUND`. Đầu vào số không hợp lệ vẫn trả 422 trước kiểm quyền dữ liệu;
  thiếu/sai token vẫn bị chặn tại lớp xác thực trước controller.
- Chỉ cập nhật `daily_rate`, không đổi trạng thái, giấy tờ, thuộc tính hay chi nhánh.
  Response 200 cùng hình dạng GET, mang giá mới. Ghi và đọc lại trong một transaction;
  lỗi khi đọc lại hoặc tra chi nhánh làm rollback.
- Gửi lại cùng giá giữ nguyên kết quả; không dùng `Idempotency-Key` vì đây là đặt giá niêm yết,
  không phải thu tiền. Không có đường xóa giá và không tính lại báo giá/đơn đã tạo (BR-234, ADR-0014).
- V008 bảo vệ ở CSDL: `chk_vehicle_daily_rate` kiểm giá; `chk_vehicle_approved_has_daily_rate`
  bắt buộc giá ở ACTIVE, INACTIVE và RETIRED. Không backfill giá phỏng đoán cho dữ liệu cũ.

## Mã lỗi

| HTTP | error.code | Khi nào |
|---|---|---|
| 401 | UNAUTHENTICATED | Thiếu hoặc sai token |
| 403 | ACCESS_DENIED | Sai quyền namespace; tạo/đọc/gửi duyệt/duyệt/đổi giá sai vai hoặc khác chi nhánh; STAFF nhập giá khi tạo |
| 403 | STAFF_BRANCH_REQUIRED | STAFF/MANAGER chưa gán khi tạo/đọc/gửi duyệt; MANAGER chưa gán khi duyệt/đổi giá |
| 400 | INVALID_REQUEST | Thiếu trường, chuỗi trắng, enum/ngày/JSON không hợp lệ |
| 422 | VEHICLE_INVALID_SEATS | Số chỗ ngoài tập `4`, `5`, `7`, `16` theo BR-018 |
| 404 | BRANCH_NOT_FOUND | Mã chi nhánh không tồn tại khi tạo |
| 404 | VEHICLE_NOT_FOUND | Xe không tồn tại khi đọc/gửi duyệt/duyệt/đổi giá |
| 409 | VEHICLE_PLATE_ALREADY_EXISTS | PostgreSQL từ chối do trùng biển số |
| 422 | VEHICLE_INVALID_STATUS_TRANSITION | Trạng thái đã sai ngay khi tải xe |
| 409 | VEHICLE_INVALID_STATUS_TRANSITION | Trạng thái hợp lệ khi đọc nhưng không còn khớp lúc ghi |
| 422 | VEHICLE_DOCUMENT_MISSING | Thiếu một hoặc cả hai ngày giấy tờ khi duyệt |
| 422 | VEHICLE_DOCUMENT_EXPIRED | Một hoặc cả hai giấy tờ hết hạn khi duyệt |
| 422 | VEHICLE_DAILY_RATE_REQUIRED | Giấy tờ hợp lệ nhưng xe chưa có giá khi duyệt |
| 422 | VEHICLE_INVALID_DAILY_RATE | Giá sai khi tạo/đổi, hoặc thiếu/null khi đổi giá |
| 415 | INVALID_REQUEST | Content-Type tạo xe hoặc đổi giá không được hỗ trợ |
| 500 | INTERNAL_ERROR | Lỗi bất ngờ, hết lượt thử mã hoặc không đọc lại được bản ghi vừa ghi |

Kiểm quyền tạo bằng `postman/task-08a-identity.postman_collection.json`, thư mục
`Part 4 - Vehicle creation authorization (BR-810)`: chạy 01–03 bằng ADMIN (xác nhận danh tính,
tạo chi nhánh thử, tạo xe có giá); đổi token thư mục sang STAFF rồi chạy 04–08 (lấy chi nhánh
mình, tạo không giá 201, gửi giá 403, tạo khác chi nhánh 403, không token 401).
STAFF phải đã được gán chi nhánh ở phần gán nhân viên. Mã tự lưu và biển số sinh ngẫu nhiên;
mỗi lượt thành công tạo một chi nhánh và hai xe DRAFT. Không chạy cả thư mục bằng một token,
không bật Share Token, dừng nếu kiểm danh tính không xanh.

Kiểm quyền gửi duyệt bằng cùng collection, thư mục
`Part 4 - Vehicle submission authorization (BR-810)`: chạy 01–04 bằng ADMIN (kiểm danh tính,
tạo chi nhánh và xe thử, gửi duyệt 200); đổi token sang STAFF rồi chạy 05–10 (lấy chi nhánh mình,
tạo xe không giá, gửi duyệt 200, gửi lại 422, gửi xe khác chi nhánh 403, không token 401).
STAFF phải đã được gán chi nhánh. Mỗi lượt tạo một chi nhánh và hai xe thử mới; không sửa việc gán,
không cần copy mã. Chạy lại cả lượt từ đầu; gửi lại riêng request đã thành công sẽ nhận lỗi trạng thái.

Kiểm quyền đổi giá bằng cùng collection, thư mục **Part 4 - Vehicle daily rate authorization (BR-810)**:
01–04 bằng STAFF lấy chi nhánh, tạo xe không giá, PUT giá bị 403 rồi GET xác nhận giá vẫn null;
05–09 bằng ADMIN xác nhận danh tính, đặt giá 855000 thành công, GET xác nhận giá mới,
thử giá 0 nhận 422 rồi GET xác nhận giá vẫn 855000; 10 No Auth nhận 401.
Mỗi lượt tạo một xe mới, không tạo/gán lại chi nhánh. Request tự lưu mã và đối chiếu toàn bộ dữ liệu
ngoài giá với lúc tạo. Chạy lại từ 01, dừng nếu setup/kiểm danh tính lỗi; không chạy cả thư mục bằng một token.

Kiểm quyền đọc bằng cùng collection, thư mục **Part 4 - Vehicle read authorization (BR-810)**:
01 bằng STAFF lấy chi nhánh; 02–05 bằng ADMIN tạo chi nhánh khác, tạo xe DRAFT và đọc 200;
06–09 bằng STAFF tạo xe ở chi nhánh mình, đọc 200 rồi đọc xe khác chi nhánh nhận 403;
10 No Auth nhận 401. Mỗi lượt tạo một chi nhánh và hai xe mới, không gán lại nhân viên.
Request tự lưu mã và so sánh GET với dữ liệu lúc tạo; chạy lại từ 01, dừng nếu setup/kiểm danh tính lỗi.
Đặt token ở thư mục, tắt Share Token; không chạy toàn bộ thư mục bằng một token.

Kiểm quyền phê duyệt bằng cùng collection, thư mục
`Part 4 - Vehicle approval authorization (BR-810)`:
01 bằng STAFF lấy chi nhánh; 02–04 bằng ADMIN tạo xe đủ giá/giấy tờ ở chi nhánh đó và gửi duyệt;
05–06 bằng STAFF chứng minh cùng chi nhánh vẫn bị 403; 07–09 bằng ADMIN duyệt chính xe đó 200
rồi thử duyệt lại 422; 10 No Auth nhận 401. Mỗi lượt tạo một xe mới, không tạo chi nhánh hay
thay việc gán nhân viên. Ngày giấy tờ thử được sinh trong tương lai; mã và dữ liệu tự lưu,
không copy giữa request. Chạy từ đầu nếu cần một lượt mới, không chạy toàn bộ bằng một token.

Hai request chuyển trạng thái cùng lúc: request đến sau có thể nhận 422 nếu đọc sau commit,
hoặc 409 nếu đã đọc trạng thái cũ nhưng thua cập nhật có điều kiện. Cả hai đều không được ghi đè.

Ví dụ lỗi:

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "VEHICLE_DOCUMENT_MISSING",
    "message": "Vehicle inspection and compulsory liability insurance expiry dates are required for approval."
  }
}
```

Thông báo code là tiếng Anh; giao diện Việt hóa sau dựa trên `error.code`.
Không trả SQL, stack trace hoặc chi tiết kết nối ra HTTP.

## Collection Postman — giá ngày

Import [collection Task 7](../../../postman/task-07-pricing.postman_collection.json)
và [environment local](../../../postman/local.postman_environment.json), chọn **Car Rental - Local**.
Khởi động backend với Flyway đã áp dụng V008, dùng Collection Runner chạy thư mục
**Part 1 - Vehicle daily rate (BR-234)** theo thứ tự 01–24. Mỗi request có `pm.test`;
các response 4xx có chủ đích phải vẫn có test xanh khi đúng mã lỗi.

Collection tạo bộ dữ liệu mẫu riêng với tên/biển số ngẫu nhiên, tự truyền mã xe/chi nhánh;
chạy lại không cần dọn dữ liệu hoặc chép biến. Giá 855.000 và 1.000.000 chỉ là dữ liệu thử.
Chưa cần biến bảo hiểm cho phần giá ngày của xe. Các collection Task 6/6b dùng giá mẫu
tường minh cho những xe cần duyệt.

Kiểm trực tiếp cả hai CHECK bằng IntelliJ SQL Console với file
`car-rental-api/src/test/resources/sql/vehicle/verify_daily_rate_constraints_manual.sql`:
chạy toàn bộ file trong một session riêng, không có transaction công việc khác.
Kết quả đúng: 8 thông báo `PASS`, các vi phạm có SQLSTATE `23514` và đúng tên constraint;
`remaining_fixture_count = 0` sau `ROLLBACK`. Nếu lỗi ngoài dự kiến, chạy `ROLLBACK` trước khi thử lại.

## Collection Postman — Task 6b, Phần 3

Import `postman/task-06b-review.postman_collection.json`, chọn **Car Rental - Local**,
khởi động lại backend rồi chạy riêng thư mục **Part 3 - Vehicle branch code** theo thứ tự 01–07.
Không cần chạy Phần 1 trước, không cần SQL hoặc chép biến giữa các request.

- 01 tạo chi nhánh; 02 tạo xe; 03 đọc DRAFT; 04 gửi duyệt; 05 duyệt; 06 đọc ACTIVE.
- Mọi response xe thành công phải có `branchCode` đúng mã chi nhánh ở request 01,
  không có `id` hoặc `branchId`, và giữ nguyên dữ liệu qua các lần chuyển trạng thái.
- 07 duyệt lại xe ACTIVE: **422 `VEHICLE_INVALID_STATUS_TRANSITION`**, test vẫn phải xanh.
- Mỗi lượt tạo một bộ dữ liệu mới với biển số/tên ngẫu nhiên và giấy tờ còn hạn;
  không xóa dữ liệu của lượt trước. Collection Task 6 cũng đã cập nhật sang hợp đồng `branchCode`.

## Collection Postman — Task 6, Phần 1

- Import [collection Task 6](../../../postman/task-06-search.postman_collection.json)
  và [environment local](../../../postman/local.postman_environment.json).
- Chọn environment `Car Rental - Local`; `baseUrl` mặc định `http://localhost:8081`.
  Nếu cổng backend local khác, chỉ đổi `baseUrl` trong Postman.
- Backend phải đang chạy và Flyway đã áp dụng V008; request tạo chi nhánh có tên/địa chỉ theo BR-808.
- Dùng Collection Runner chạy toàn bộ thư mục `Part 1 - Vehicle attributes (BR-018)`
  theo thứ tự 01–18. Không cần token hoặc copy mã giữa các request.
- Request 01 tự tạo chi nhánh và lưu mã; request 02 sinh biển số ngẫu nhiên, tạo xe
  rồi lưu mã cùng dữ liệu để các request sau đối chiếu. Ngày hết hạn giấy tờ được
  sinh ở tương lai cho bộ dữ liệu thử, không phải chính sách nghiệp vụ.
- Request 03–06 kiểm đọc lại và giữ nguyên thuộc tính qua gửi duyệt/phê duyệt.
- Request 07 kiểm số chỗ 6 (`422 / VEHICLE_INVALID_SEATS`); 08–11 kiểm thiếu từng
  thuộc tính; 12–15 kiểm từng thuộc tính null; 16–17 kiểm hãng/dòng trắng; 18 kiểm
  hộp số lạ (các trường hợp này là `400 / INVALID_REQUEST`).
- Mỗi request có `pm.test` kiểm mã HTTP, envelope và dữ liệu/mã lỗi tương ứng.
  Không coi request phá hoại là trượt chỉ vì HTTP 400/422: test phải xanh khi lỗi
  đúng hợp đồng. Không dùng Collection Runner với cấu hình dừng chỉ vì HTTP 4xx.
- Chạy lại cả thư mục không cần dọn CSDL: mỗi lượt tạo thêm một chi nhánh và một xe
  mẫu mới. Collection không tự xóa dữ liệu, không ghi lịch; giữ lại mã fixture khi
  cần dọn có kiểm soát. Trước lần áp dụng V007, xử lý dữ liệu cũ theo hướng dẫn Phần 2.
- Phép thử constraint SQL chạy riêng trong IntelliJ SQL Console bằng transaction
  rồi `ROLLBACK`; collection không thay thế việc kiểm constraint tại PostgreSQL.
