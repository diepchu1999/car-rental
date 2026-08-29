# Bàn giao Task 4 — shared, branch, vehicle

## Trạng thái

Đã bổ sung các file còn thiếu của luồng xe và tài liệu liên quan.
**Chưa chạy build, test, Docker, ứng dụng hoặc gọi API trong lượt hoàn thiện này**, theo yêu cầu
của Tech Owner. Các lệnh dưới đây dành cho Tech Owner tự chạy; không phải báo cáo kết quả đã đạt.
Chỉ chốt nghiệm thu sau khi `clean verify` và kiểm local thành công.

Không sửa migration V001/V002/V003, không thay image/platform, không sửa `.env` và không ghi vào Git.
Đã sửa tên package `persistance` còn sót trong VehicleConstraintIntegrationTest thành `persistence`;
file đã nằm ở thư mục đúng, không thay hành vi test.

## Bảy lớp shared — giữ nguyên phạm vi đã duyệt

| Lớp | Vì sao cần ngay |
|---|---|
| ApiResponse | Cấu trúc response chung cho branch và vehicle |
| ErrorCode | Mã lỗi ổn định cho hai API |
| DomainException | Lỗi có chủ đích, không phụ thuộc HTTP/JDBC |
| ApiExceptionHandler | Ánh xạ lỗi và chuẩn hóa response Spring MVC |
| Validations | Kiểm trường bắt buộc dùng chung, không nhân bản kiểm null/trắng |
| SqlLoader | Đọc file SQL UTF-8, không viết SQL inline trong Java |
| BusinessCodeGenerator | Sinh mã CN/XE dùng chung; UNIQUE ở database quyết định tính duy nhất |

Không thêm tiện ích shared mới. Đồng hồ duyệt xe nằm trong module vehicle.

## Endpoint

Xem hợp đồng và mã lỗi đầy đủ tại [branch](../api/branch.md) và [vehicle](../api/vehicle.md).

| Method | Path | Kết quả chính |
|---|---|---|
| POST | /api/v1/admin/branches | 201, mã CN và tọa độ |
| GET | /api/v1/admin/branches/{code} | 200 hoặc BRANCH_NOT_FOUND/404 |
| POST | /api/v1/admin/vehicles | 201, COMPANY/DRAFT; 400/404/409 khi đầu vào lỗi |
| GET | /api/v1/admin/vehicles/{code} | 200 hoặc VEHICLE_NOT_FOUND/404 |
| POST | /api/v1/admin/vehicles/{code}/submit-for-approval | 200/PENDING_APPROVAL; 404/422/409 |
| POST | /api/v1/admin/vehicles/{code}/approve | 200/ACTIVE; 404/422/409, gồm lỗi giấy tờ |

## Các quyết định kỹ thuật

- Sinh mã ở application: `CN-`/`XE-` cộng sáu ký tự A–Z, 0–9 bằng SecureRandom.
- Database UNIQUE bảo vệ mã và biển số. `ON CONFLICT (code) DO NOTHING` cho phép thử mã khác
  trong cùng transaction; tối đa năm lần tính cả lần đầu. Không thử lại mọi lỗi database.
- Trùng biển số được adapter xác định bằng SQLSTATE, schema, table, constraint rồi dịch sang
  exception của port; service chuyển thành VEHICLE_PLATE_ALREADY_EXISTS/409.
- Gửi duyệt và duyệt dùng domain + UPDATE có điều kiện trạng thái cũ. Domain sai trạng thái là 422;
  thua tranh chấp khi ghi là 409. Không nới R6 và không rẽ nhánh theo OwnershipType.
- Clock cho duyệt dùng Asia/Ho_Chi_Minh; unit test dùng giờ cố định qua biên ngày UTC/Việt Nam.
- Response xe hiện trả branchId chỉ đọc, không trả id xe. Tạo xe vẫn nhận branchCode, URL dùng code.
  Nếu Chief Architect muốn response chỉ chứa mã chi nhánh, cần mở rộng cổng branch.api để tra cứu
  ngược, không JOIN schema hoặc tự suy code từ id.
- Trường JSON ngoài hợp đồng tạo xe bị bỏ qua; không có đường HTTP chọn PARTNER/ACTIVE.
- Chưa có quyền BRANCH_MANAGER: xác thực/phân quyền được loại khỏi task theo phạm vi đã duyệt.

## 1. Build và test — Tech Owner chạy

Yêu cầu Docker Desktop đang chạy; Testcontainers tạo PostgreSQL riêng, không dùng volume local.
Không cần nạp `.env` cho test; cấu hình test đọc image/platform từ `.env.example` đã commit.

```bash
cd /Users/diepchu/project/car-rental/car-rental-api
./mvnw clean verify
```

Kỳ vọng `BUILD SUCCESS`, không có test failure/error; ArchitectureRulesTest cũng xanh.
Không cần chạy các nhóm dưới đây nếu toàn bộ verify đã xanh; chúng phục vụ khoanh vùng khi lỗi.

Unit test phần service bổ sung (không cần Docker):

```bash
./mvnw '-Dtest=VehicleCreationCodeTest,VehicleCommandServiceTest,VehicleQueryServiceTest' test
```

Service/API/đồng thời trên PostgreSQL thật:

```bash
./mvnw '-Dtest=VehicleServiceIntegrationTest,VehicleApiIntegrationTest,VehicleStatusConcurrencyIntegrationTest' test
```

Bốn bất biến bắt buộc và PostGIS:

```bash
./mvnw '-Dtest=VehicleConstraintIntegrationTest,BranchConstraintIntegrationTest,BranchPostgisIntegrationTest' test
```

| Phép thử | Điều kiện đúng, không chỉ ném exception |
|---|---|
| Đổi ownership_type | SQLSTATE 23514, chk_vehicle_ownership_type_immutable |
| Trùng biển số | SQLSTATE 23505, uq_vehicle_plate_number |
| COMPANY thiếu branch_id | SQLSTATE 23514, chk_vehicle_company_has_branch |
| Chi nhánh thiếu tọa độ | SQLSTATE 23502, schema branch, table branch, column location |
| POINT EMPTY | SQLSTATE 23514, chk_branch_location_not_empty |
| Trùng mã | SQLSTATE 23505 và đúng uq_branch_code/uq_vehicle_code |
| PostGIS | Lưu/đọc đúng tọa độ, ST_DWithin chọn đúng nhánh, có GiST index |

Các phép thử ràng buộc dùng transaction rollback trong database test; không phá dữ liệu local.
VehicleStatusConcurrencyIntegrationTest giữ transaction đầu chưa commit và xác nhận transaction
thứ hai thực sự bị chặn qua PostgreSQL trước khi thả, không chỉ chạy tuần tự hai lần UPDATE.
Service integration test dùng proxy Spring thật, chứng minh commit và rollback cả tạo lẫn chuyển trạng thái.

## 2. Khởi động local

Từ terminal thứ nhất:

```bash
cd /Users/diepchu/project/car-rental
docker compose up -d
docker compose ps
set -a
. ./.env
set +a
cd car-rental-api
./mvnw spring-boot:run
```

Kỳ vọng ba dịch vụ healthy; backend khởi động và Flyway chỉ chạy migration chưa có trong history.
Không sửa checksum, không xóa volume để né lỗi migration đã chạy.
Nếu dùng IntelliJ, làm theo phần Chạy local trong README gốc thay lệnh spring-boot:run.

## 3. Thử vòng đời bằng HTTP

Terminal thứ hai, dùng cổng trong `.env`; các request bên dưới tạo dữ liệu local thật và giữ lại.

```bash
cd /Users/diepchu/project/car-rental
set -a
. ./.env
set +a
API_BASE="http://127.0.0.1:${CAR_RENTAL_API_PORT}"
curl --fail-with-body --silent --show-error "$API_BASE/actuator/health"
```

Kỳ vọng status tổng thể UP và db UP.

Tạo chi nhánh:

```bash
curl --fail-with-body --silent --show-error -i \
  -H 'Content-Type: application/json' \
  -d '{"latitude":10.762622,"longitude":106.660172}' \
  "$API_BASE/api/v1/admin/branches"
```

Kỳ vọng 201, success=true, mã CN, tọa độ đúng và Location. Nhập mã nhận được khi chạy:

```bash
printf 'Branch code from the response: '
read -r BRANCH_CODE
curl --fail-with-body --silent --show-error -i \
  "$API_BASE/api/v1/admin/branches/$BRANCH_CODE"
```

Kỳ vọng 200, dữ liệu bằng response tạo chi nhánh.
Tạo xe với biển số thử riêng; ngày mẫu 2099 dùng riêng cho demo để không hết hạn sớm:

```bash
DEMO_PLATE="LOCAL-$(date +%Y%m%d%H%M%S)"
curl --fail-with-body --silent --show-error -i \
  -H 'Content-Type: application/json' \
  -d "{\"plateNumber\":\"$DEMO_PLATE\",\"fuelType\":\"PETROL\",\"branchCode\":\"$BRANCH_CODE\",\"inspectionExpiresOn\":\"2099-12-31\",\"liabilityInsuranceExpiresOn\":\"2099-12-31\"}" \
  "$API_BASE/api/v1/admin/vehicles"
printf '\nVehicle code from the response: '
read -r VEHICLE_CODE
curl --fail-with-body --silent --show-error -i \
  "$API_BASE/api/v1/admin/vehicles/$VEHICLE_CODE"
```

Kỳ vọng tạo 201, đọc 200, ownershipType=COMPANY, status=DRAFT, ngày đúng và branchId có giá trị.

```bash
curl --fail-with-body --silent --show-error -i -X POST \
  "$API_BASE/api/v1/admin/vehicles/$VEHICLE_CODE/submit-for-approval"
curl --fail-with-body --silent --show-error -i -X POST \
  "$API_BASE/api/v1/admin/vehicles/$VEHICLE_CODE/approve"
curl --fail-with-body --silent --show-error -i \
  "$API_BASE/api/v1/admin/vehicles/$VEHICLE_CODE"
```

Kỳ vọng lần lượt 200/PENDING_APPROVAL, 200/ACTIVE, 200/ACTIVE. Các trường khác không đổi.

Thử duyệt lần nữa — bỏ --fail-with-body để quan sát lỗi dự kiến:

```bash
curl --silent --show-error -i -X POST \
  "$API_BASE/api/v1/admin/vehicles/$VEHICLE_CODE/approve"
```

Kỳ vọng 422, success=false, data=null, error.code=VEHICLE_INVALID_STATUS_TRANSITION.
Các trường hợp thiếu/hết hạn giấy tờ và trùng biển số đã có trong VehicleApiIntegrationTest.

## 4. Repo sạch về secret và file sinh tự động

```bash
cd /Users/diepchu/project/car-rental
git status --short
git check-ignore .env .DS_Store car-rental-api/target/
```

Kỳ vọng status chỉ hiện source/tài liệu dự kiến; không hiện .env, .DS_Store hoặc target.
check-ignore phải in ba đường dẫn đã được ignore. Không đưa nội dung .env vào log review.

## Phần cần Chief Architect cân nhắc

- BR-807 và managerId chờ identity; không tạo cột hoặc giá trị giả trong Task 4.
- Không có API sửa giấy tờ/xe ở phạm vi hiện tại; hồ sơ thiếu giấy tờ chưa thể hoàn thiện qua API.
- Trước khi triển khai ngoài local, bổ sung xác thực và quyền duyệt BR-010, ranh giới chi nhánh.
- Kiểm hợp đồng response branchId chỉ đọc và quy ước ngày duyệt Việt Nam trước khi phát hành API.
- Khóa lịch khi giấy tờ hết hạn vẫn thuộc availability ở Task 5, không thêm vào task này.

## Đề xuất commit message

`feat: implement branch and vehicle lifecycle with native SQL and architecture coverage`

Tech Owner tự xem diff và stage/commit sau khi nghiệm thu. Worktree có cả thay đổi của các task
trước; không gom toàn bộ theo quán tính. Codex không thực hiện thao tác Git có ghi.
