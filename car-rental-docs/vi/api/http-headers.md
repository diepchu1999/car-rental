# Header truy vết HTTP

Áp dụng cho mọi request đi qua filter ứng dụng, gồm API và `/actuator/health`.
Đây là hợp đồng kỹ thuật theo security-guideline §3, không đổi quy tắc nghiệp vụ hoặc JSON response.

- Response có header `X-Request-Id`, cả khi API trả lỗi.
- Client có thể gửi **một** `X-Request-Id` khớp toàn bộ `[A-Za-z0-9-]{1,64}`. Server dùng lại nguyên vẹn.
- Thiếu header, nhiều giá trị, quá dài hoặc có ký tự cấm: server sinh UUID mới và trả về header.
  Không trim giá trị, không dùng lại giá trị bị loại, không ghi giá trị bị loại vào log.
- ID chỉ dùng để đối chiếu log, không phải thông tin xác thực, quyền truy cập hay idempotency key.
- JSON giữ nguyên cấu trúc cũ; không thêm trường `requestId`, không đưa chi tiết nội bộ vào lỗi 500.
- Lỗi giao thức bị HTTP server từ chối **trước filter** không thuộc bảo đảm này. Không gửi CR/LF thật
  từ Postman; trường hợp đó được kiểm trực tiếp trong test filter.

Ví dụ request header hợp lệ:

```http
X-Request-Id: local-check-123
```

Response phải có đúng `X-Request-Id: local-check-123`.
Các header `X-Client-Platform` và `X-Client-Version` vẫn dùng như trước.

Kiểm bằng collection `postman/task-logging.postman_collection.json`, thư mục **Part 2 - Request ID**.
Xem cách đối chiếu log ở mục **Theo dõi request bằng requestId** trong README gốc.
