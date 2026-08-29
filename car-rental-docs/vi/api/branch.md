# API chi nhánh — admin

## Phạm vi

Task 4 cung cấp chức năng tạo và đọc danh tính, vị trí chi nhánh.

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
```

Body:

```json
{
  "latitude": 10.762622,
  "longitude": 106.660172
}
```

| Trường | Kiểu JSON | Bắt buộc | Điều kiện |
|---|---|---|---|
| `latitude` | number | Có | Hữu hạn, từ -90 đến 90, bao gồm hai biên |
| `longitude` | number | Có | Hữu hạn, từ -180 đến 180, bao gồm hai biên |

- Không chấp nhận thiếu hoặc `null` ở một trong hai tọa độ.
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
    "longitude": 106.660172
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
  "longitude": 106.660172
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
    "longitude": 106.660172
  },
  "error": null
}
```

Response chỉ có mã nghiệp vụ và tọa độ trong `data`.
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
| POST | 400 | `INVALID_REQUEST` | JSON không đọc được, thiếu hoặc null tọa độ, tọa độ không hữu hạn hoặc ngoài giới hạn |
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