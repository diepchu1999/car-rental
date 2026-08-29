# API xe — admin

## Phạm vi Task 4

- BR-001: lưu loại sở hữu, bất biến sau khi tạo; API GĐ1 chỉ tạo `COMPANY`.
- BR-003: tra cứu chi nhánh đã tồn tại qua `branch.api.BranchDirectory` trước khi tạo xe.
- BR-005: lưu ngày hết hạn; kiểm có đủ và còn hạn tại bước duyệt.
- BR-010 và status-flow §4: `DRAFT → PENDING_APPROVAL → ACTIVE`.
- BR-410: `PETROL`, `DIESEL`, `ELECTRIC`, `HYBRID`.
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
  "branchCode": "CN-3TR7WK",
  "inspectionExpiresOn": "2030-12-31",
  "liabilityInsuranceExpiresOn": "2030-12-31"
}
```

Ba trường `plateNumber`, `fuelType`, `branchCode` bắt buộc. Chuỗi không được trắng.
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
