# API xe — admin

## Phạm vi Task 4 và Task 6 — Phần 1

- BR-001: lưu loại sở hữu, bất biến sau khi tạo; API GĐ1 chỉ tạo `COMPANY`.
- BR-003: tra cứu chi nhánh đã tồn tại qua `branch.api.BranchDirectory` trước khi tạo xe.
- BR-005: lưu ngày hết hạn; kiểm có đủ và còn hạn tại bước duyệt.
- BR-010 và status-flow §4: `DRAFT → PENDING_APPROVAL → ACTIVE`.
- BR-410: `PETROL`, `DIESEL`, `ELECTRIC`, `HYBRID`.
- BR-018: bắt buộc số chỗ, hộp số, hãng và dòng xe ngay khi tạo.
- Không xác thực/phân quyền trong task này. Namespace admin không tự bảo vệ API.
- Không xử lý availability, partner onboarding, điều chuyển, sửa giấy tờ hoặc thanh lý.

## Endpoint

| Method | Path | Thành công |
|---|---|---|
| POST | `/api/v1/admin/vehicles` | 201, Location trỏ tới xe mới |
| GET | `/api/v1/admin/vehicles/{code}` | 200 |
| POST | `/api/v1/admin/vehicles/{code}/submit-for-approval` | 200, PENDING_APPROVAL |
| POST | `/api/v1/admin/vehicles/{code}/approve` | 200, ACTIVE |

URL luôn dùng mã `XE-<6>`, không dùng khóa chính số.
Tra cứu giữ nguyên mã đầu vào, không cắt khoảng trắng hoặc đổi kiểu chữ.

## Tạo xe

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
  "liabilityInsuranceExpiresOn": "2030-12-31"
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
    "branchId": 42,
    "status": "DRAFT",
    "inspectionExpiresOn": "2030-12-31",
    "liabilityInsuranceExpiresOn": "2030-12-31"
  },
  "error": null
}
```

`branchId` là tham chiếu chi nhánh chỉ đọc trong API admin hiện tại, không phải input hoặc URL.
Không trả `id` của xe, không JOIN schema branch để bổ sung dữ liệu ngoài hợp đồng hiện có.
Nếu cần trả `branchCode`/thông tin chi nhánh trong tương lai, bổ sung tra cứu qua `branch.api`,
không suy mã từ ID và không đọc thẳng bảng của module khác.

Service thử tối đa năm mã `XE-<6>` nếu trùng mã. Trùng biển số dừng ngay, không thử lại.
Endpoint chưa hỗ trợ Idempotency-Key; gửi lại cùng biển số sẽ nhận 409.

## Đọc, gửi duyệt và phê duyệt

GET trả cùng hình dạng dữ liệu như POST tạo xe, kể cả giấy tờ null hoặc đã hết hạn.
Gửi duyệt và phê duyệt giữ nguyên cả bốn thuộc tính BR-018.

Hai POST chuyển trạng thái không cần body. Body không được dùng để chọn trạng thái,
ngày duyệt hoặc sửa thông tin giấy tờ.

1. Gửi duyệt chỉ nhận DRAFT và trả PENDING_APPROVAL; chưa kiểm đủ giấy tờ.
2. Duyệt chỉ nhận PENDING_APPROVAL. Cả đăng kiểm và TNDS phải có ngày hết hạn
   **sau ngày duyệt**; ngày hết hạn bằng ngày duyệt cũng bị từ chối.
3. Ngày duyệt là ngày server theo `Asia/Ho_Chi_Minh`, không phụ thuộc timezone mặc định của JVM.
   Đây là quy ước kỹ thuật của Task 4 cho triển khai tại Việt Nam.
4. Cập nhật dùng `WHERE code = :code AND status = :expectedStatus`; không ghi đè trạng thái mới hơn.
5. Ghi và đọc lại nằm trong cùng transaction. Lỗi runtime sau ghi làm rollback thao tác đó.

Không có endpoint sửa giấy tờ trong Task 4. Hồ sơ chưa đủ giấy tờ được dùng để chứng minh cổng
duyệt chặn đúng; đừng thêm endpoint sửa chỉ để hoàn thành ví dụ này.

## Mã lỗi

| HTTP | error.code | Khi nào |
|---|---|---|
| 400 | INVALID_REQUEST | Thiếu trường, chuỗi trắng, enum/ngày/JSON không hợp lệ |
| 422 | VEHICLE_INVALID_SEATS | Số chỗ ngoài tập `4`, `5`, `7`, `16` theo BR-018 |
| 404 | BRANCH_NOT_FOUND | Mã chi nhánh không tồn tại khi tạo |
| 404 | VEHICLE_NOT_FOUND | Xe không tồn tại khi đọc/gửi duyệt/duyệt |
| 409 | VEHICLE_PLATE_ALREADY_EXISTS | PostgreSQL từ chối do trùng biển số |
| 422 | VEHICLE_INVALID_STATUS_TRANSITION | Trạng thái đã sai ngay khi tải xe |
| 409 | VEHICLE_INVALID_STATUS_TRANSITION | Trạng thái hợp lệ khi đọc nhưng không còn khớp lúc ghi |
| 422 | VEHICLE_DOCUMENT_MISSING | Thiếu một hoặc cả hai ngày giấy tờ khi duyệt |
| 422 | VEHICLE_DOCUMENT_EXPIRED | Một hoặc cả hai giấy tờ hết hạn khi duyệt |
| 415 | INVALID_REQUEST | Content-Type tạo xe không được hỗ trợ |
| 500 | INTERNAL_ERROR | Lỗi bất ngờ, hết lượt thử mã hoặc không đọc lại được bản ghi vừa ghi |

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

## Collection Postman — Task 6, Phần 1

- Import [collection Task 6](../../../postman/task-06-search.postman_collection.json)
  và [environment local](../../../postman/local.postman_environment.json).
- Chọn environment `Car Rental - Local`; `baseUrl` mặc định `http://localhost:8081`.
  Nếu cổng backend local khác, chỉ đổi `baseUrl` trong Postman.
- Backend phải đang chạy và Flyway đã áp dụng V007; request tạo chi nhánh đã bổ sung tên/địa chỉ theo BR-808.
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
