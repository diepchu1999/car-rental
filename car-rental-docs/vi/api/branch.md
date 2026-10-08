# API chi nhánh — admin

## Phạm vi

Task 4 cung cấp chức năng tạo và đọc danh tính, vị trí chi nhánh.
Task 6 Phần 2 bổ sung tên và địa chỉ bắt buộc theo BR-808 ngay trong v1,
theo ngoại lệ thay đổi trước phát hành của api-guideline.

- Vị trí chi nhánh phục vụ BR-003: xe công ty sử dụng vị trí chi nhánh khi tìm kiếm.
- Mã chi nhánh theo database-guideline mục 2: `CN-` và sáu ký tự thuộc `A-Z`, `0-9`.
- Mã do application sinh; cơ sở dữ liệu bảo vệ tính duy nhất.
- Chưa lưu `managerId`. BR-807 được hiện thực cùng module identity ở task sau.
- Chỉ chạy local. Chưa có xác thực và phân quyền.
- Namespace `/admin` không tự bảo vệ quyền truy cập.

Tài liệu liên quan:

- [Quy tắc nghiệp vụ](../business/business-rules.md)
- [Cấu trúc module](../architecture/module-architecture.md)
- [Quy ước cơ sở dữ liệu](../architecture/database-guideline.md)
- [Quy ước API](../architecture/api-guideline.md)
- [Danh mục mã lỗi](../architecture/backend-guideline.md)

## Danh mục endpoint

| HTTP method | Path | Chức năng | Thành công |
|---|---|---|---|
| POST | `/api/v1/admin/branches` | Tạo chi nhánh | 201 |
| GET | `/api/v1/admin/branches/{code}` | Đọc chi nhánh theo mã nghiệp vụ | 200 |

Không có endpoint danh sách, cập nhật hoặc đóng chi nhánh trong phạm vi này.

## POST /api/v1/admin/branches

### Request

Header:

```http
Content-Type: application/json
Accept: application/json
X-Client-Platform: web
X-Client-Version: 0.0.1
```

Body:

```json
{
  "latitude": 10.762622,
  "longitude": 106.660172,
  "name": "Central Branch",
  "address": "123 Main Street"
}
```

| Trường | Kiểu JSON | Bắt buộc | Điều kiện |
|---|---|---|---|
| `name` | string | Có | Không null, rỗng hoặc chỉ chứa khoảng trắng |
| `address` | string | Có | Không null, rỗng hoặc chỉ chứa khoảng trắng |
| `latitude` | number | Có | Hữu hạn, từ -90 đến 90, bao gồm hai biên |
| `longitude` | number | Có | Hữu hạn, từ -180 đến 180, bao gồm hai biên |

- Không chấp nhận thiếu hoặc `null` ở một trong hai tọa độ.
- Tên và địa chỉ được giữ nguyên cách viết, không tự cắt khoảng trắng hay đổi kiểu chữ.
- Không áp độ dài tối đa hoặc tính duy nhất cho tên/địa chỉ khi BR-808 chưa quy định.
- Tọa độ bằng `0` hợp lệ.
- Không tự làm tròn hoặc thay tọa độ thiếu bằng `0`.
- `code`, `id` và `managerId` không thuộc hợp đồng request.
- Request không quyết định mã chi nhánh được tạo.

### Response thành công

HTTP status: `201 Created`.

Header mẫu:

```http
Location: /api/v1/admin/branches/CN-3TR7WK
Content-Type: application/json
```

`Location` trỏ đến endpoint đọc chi nhánh vừa tạo.
Giá trị mã trong ví dụ chỉ là minh họa; mã thực tế do server sinh.

Body mẫu:

```json
{
  "success": true,
  "data": {
    "code": "CN-3TR7WK",
    "latitude": 10.762622,
    "longitude": 106.660172,
    "name": "Central Branch",
    "address": "123 Main Street"
  },
  "error": null
}
```

Service chèn và đọc lại dữ liệu trong cùng transaction.
Nếu bước đọc lại phát sinh lỗi runtime, bản ghi vừa chèn phải được rollback.

### Ví dụ lỗi thiếu tọa độ

Request:

```json
{
  "longitude": 106.660172,
  "name": "Central Branch",
  "address": "123 Main Street"
}
```

HTTP status: `400 Bad Request`.

Body:

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "INVALID_REQUEST",
    "message": "latitude is required."
  }
}
```

### Thử lại và idempotency

Endpoint chưa hỗ trợ `Idempotency-Key`.

Nếu mã vừa sinh đã tồn tại, service có thể sinh mã khác và thử chèn lại.
Giới hạn kỹ thuật hiện tại là tổng cộng năm lần thử, tính cả lần đầu tiên.
Hết lượt thử được xử lý như lỗi nội bộ.

Cơ chế này không chống tạo trùng do gửi lại HTTP request.
Hai request hợp lệ có cùng tọa độ có thể tạo hai chi nhánh khác nhau.

## GET /api/v1/admin/branches/{code}

### Request

Ví dụ:

```http
GET /api/v1/admin/branches/CN-3TR7WK
Accept: application/json
```

- `code` là mã nghiệp vụ, không phải khóa chính số.
- Endpoint không nhận request body.
- Mã được truyền nguyên trạng vào truy vấn, không tự đổi kiểu chữ hoặc cắt khoảng trắng.
- Không áp lại quy tắc định dạng tạo mã tại query.
- Không tìm thấy bản ghi tương ứng thì trả `BRANCH_NOT_FOUND`.

### Response thành công

HTTP status: `200 OK`.

```json
{
  "success": true,
  "data": {
    "code": "CN-3TR7WK",
    "latitude": 10.762622,
    "longitude": 106.660172,
    "name": "Central Branch",
    "address": "123 Main Street"
  },
  "error": null
}
```

Response có `code`, `latitude`, `longitude`, `name`, `address` trong `data`.
Không công khai trường `id` của application view.

### Response không tìm thấy

HTTP status: `404 Not Found`.

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "BRANCH_NOT_FOUND",
    "message": "Branch not found."
  }
}
```

## Các lỗi chính

| Endpoint | HTTP | error.code | Trường hợp |
|---|---|---|---|
| POST | 400 | `INVALID_REQUEST` | JSON không đọc được, thiếu hoặc null tọa độ, tọa độ không hữu hạn hoặc ngoài giới hạn; thiếu/null/rỗng/trắng tên hoặc địa chỉ |
| POST | 415 | `INVALID_REQUEST` | Content-Type không được endpoint hỗ trợ |
| GET | 404 | `BRANCH_NOT_FOUND` | Không tìm thấy chi nhánh theo mã |
| POST, GET | 500 | `INTERNAL_ERROR` | Lỗi hệ thống ngoài dự kiến |

Lỗi hệ thống sử dụng thông báo công khai:

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "INTERNAL_ERROR",
    "message": "An unexpected error occurred."
  }
}
```

Không đưa stack trace, câu SQL hoặc thông tin kết nối vào response.
Phía gọi xử lý dựa trên `error.code`, không dựa trên nội dung `error.message`.

## Lỗi tên và địa chỉ — BR-808

Thiếu hoặc null trả `400 / INVALID_REQUEST`, với thông báo
`name is required.` hoặc `address is required.`.
Chuỗi rỗng hoặc trắng trả cùng mã lỗi, với thông báo
`name must not be blank.` hoặc `address must not be blank.`.

## Collection Postman — Task 6, Phần 2

- Import [collection Task 6](../../../postman/task-06-search.postman_collection.json)
  và [environment local](../../../postman/local.postman_environment.json).
- Chọn environment `Car Rental - Local`; backend phải đã áp dụng V007.
- Chạy thư mục `Part 2 - Branch display fields (BR-808)` theo thứ tự 01–10.
- 01 tạo chi nhánh với tên ngẫu nhiên và tự lưu mã; 02 đọc lại đầy đủ tên/địa chỉ.
- 03–10 cố tình thiếu, null, rỗng hoặc trắng từng trường; phải nhận
  `400 / INVALID_REQUEST` với đúng thông báo. Mỗi request có ba `pm.test`.
- Có thể chạy lại toàn bộ thư mục nhiều lần. Mỗi lượt tạo thêm một chi nhánh;
  không tự xóa dữ liệu và không cần copy mã giữa các request.
- Trước lần áp dụng V007, kiểm tra dữ liệu mẫu local: migration không điền tên hay
  địa chỉ phỏng đoán cho bản ghi cũ. Nếu còn chi nhánh, phải xử lý dữ liệu đã được
  Tech Owner xác nhận trước khi khởi động app; không xóa volume một cách mặc định.
