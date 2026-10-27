# Ảnh giả cho luồng nộp giấy tờ local

Chỉ dùng với tài khoản CUSTOMER thử. Đây là hình chữ nhật hai màu, không phải CCCD/GPLX hoặc ảnh của người thật.

| File | Nội dung | Kết quả khi nộp |
|---|---|---|
| `local-front.png` | PNG 64 × 40, hai màu | 201 PENDING nếu AN hợp lệ |
| `local-back.png` | PNG 64 × 40, đảo hai màu | 201 PENDING nếu AN hợp lệ |
| `invalid-image.jpg` | Văn bản, cố tình đặt đuôi ảnh | 422 DOCUMENT_IMAGE_INVALID |
| `oversized-6mb.png` | PNG mẫu nối byte 0 thành đúng 6.000.000 byte | 422 DOCUMENT_IMAGE_TOO_LARGE với giới hạn local 5.000.000 byte |

Trong Postman Settings → General, đặt **Working directory** là thư mục gốc repo, ví dụ
`/Users/diepchu/project/car-rental`. File binary trong collection dùng đường dẫn tương đối
`postman/fixtures/documents/<tên file>`. Dùng Postman desktop và file local, không upload fixture lên cloud.
Nếu báo không đọc được file, kiểm Working directory và đường dẫn ở Body → binary.

Import `postman/task-08b-documents.postman_collection.json`, chọn `Car Rental - Local`.
Ở Authorization của thư mục **Part 2 - Submit documents (BR-130)**, lấy token CUSTOMER bằng PKCE và Use Token.
Chạy cả thư mục bằng Collection Runner, **1 iteration**, từ request 01; mỗi request có `pm.test`.
MinIO phải đã có bucket riêng tư và tài khoản ứng dụng theo `infra/minio/README.md`.
Khởi động lại backend sau khi build code mới.

Collection xin AN/URL mới mỗi lượt, PUT file rồi nộp CCCD và GPLX. URL chỉ ở biến local của lượt Runner,
không lưu vào environment/collection; các request PUT dùng No Auth, không chuyển bearer token sang MinIO.
Không chạy rời request vì biến URL không tồn tại ngoài lượt Runner và URL chỉ có hạn 5 phút.
Để chạy lại, bắt đầu từ request 01; nếu token hết hạn thì lấy token mới trước.

HTTP 400/401/409/422 trong các ca từ chối là kết quả đúng nếu Test Results đều xanh.
Runner tạo dữ liệu thử và thay bản PENDING trước, giữ nguyên bản VERIFIED đang dùng. Không tự xoá hồ sơ/ảnh.
Không bật Share/Sync Token, không export token, URL ký hoặc response thật vào repo.
