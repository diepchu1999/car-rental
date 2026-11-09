# Kho ảnh giấy tờ local

BR-130: bucket riêng tư, backend dùng tài khoản ứng dụng, không dùng tài khoản root.
Chỉ thử bằng ảnh giả, không tải giấy tờ thật vào môi trường local.

## Cấu hình

Thêm tám biến sau vào `.env` ở gốc repo. Giữ tên biến khớp `.env.example`:

```dotenv
CAR_RENTAL_STORAGE_ENDPOINT=http://localhost:9000
CAR_RENTAL_STORAGE_BUCKET=car-rental-documents
CAR_RENTAL_STORAGE_ACCESS_KEY=car-rental-documents-app
CAR_RENTAL_STORAGE_SECRET_KEY=
CAR_RENTAL_DOCUMENT_MAX_BYTES=5000000
CAR_RENTAL_DOCUMENT_MAX_PIXELS=40000000
CAR_RENTAL_DOCUMENT_UPLOAD_LIFETIME=PT5M
CAR_RENTAL_DOCUMENT_VIEW_LIFETIME=PT60S
```

Sinh secret riêng bằng `openssl rand -hex 32` rồi tự điền vào biến đang trống. Không chia sẻ kết quả.
Tài khoản ứng dụng phải là tài khoản riêng mới, không tái sử dụng tài khoản đã có quyền rộng hơn;
script không gỡ các policy khác của tài khoản có sẵn. Không dùng `MINIO_ROOT_USER` làm access key.
Không thay các credential MinIO root đang dùng.

Endpoint là cổng **API 9000**, không phải console 9001; nếu đổi cổng local thì đổi endpoint tương ứng.
URL đã ký không được đổi `localhost` thành `127.0.0.1`, hoặc đổi host/port/query sau khi nhận.
Backend chạy trên máy nên dùng endpoint truy cập được từ máy và Postman.

## Khởi tạo

Chạy từ thư mục gốc repo sau khi Docker Desktop đã chạy:

```bash
docker compose config --quiet
docker compose up -d minio
docker compose exec -T minio sh < infra/minio/bootstrap-documents.sh
```

Chờ MinIO healthy nếu lần đầu khởi động. Kết quả đúng của script:
`Private document bucket and application account are ready.` Chạy lại cùng cấu hình không xoá ảnh.
Không bật `set -x`, không chạy `docker compose config` không có `--quiet` trong log chia sẻ.
Không cần xoá volume. Nếu có lỗi, kiểm biến bắt buộc, MinIO đã ready và credential root đang đúng;
script cố ý không in phản hồi có thể chứa credential.

Quyền ứng dụng: đọc/ghi object trong `incoming/` và `sealed/`, đọc vị trí bucket; không có quyền xoá
object, liệt kê tất cả bucket hoặc quản trị. Chỉ script khởi tạo dùng root; credential tạm của `mc`
nằm trong thư mục quyền hạn chế và được xoá khi script kết thúc.
MinIO có thể trả danh sách bucket đã lọc theo quyền thay vì báo AccessDenied khi gọi `listBuckets()`;
tài khoản này chỉ được nhìn thấy bucket giấy tờ, không được đọc object ở bucket khác.

Nạp lại biến vào môi trường chạy backend (terminal hoặc IntelliJ), rồi khởi động lại backend.
Thiếu cấu hình hoặc giới hạn không hợp lệ làm startup lỗi, không âm thầm dùng root.

## Cách lưu và kiểm ảnh

URL PUT chỉ ghi vào vùng `incoming/`. Sau khi kiểm bytes và kích thước, backend ghi chính bytes
đã kiểm sang một key ngẫu nhiên mới trong `sealed/`; URL PUT cũ không thay được ảnh đã chốt.
JPEG/PNG được giải mã sau khi kiểm pixel. HEIC được kiểm cấu trúc container/HEVC và kích thước,
không giải mã HEVC sang bitmap, không OCR hay khẳng định giấy tờ thật.
URL xem GET hết hạn sau 60 giây và yêu cầu không cache; mở trực tiếp object không chữ ký bị từ chối.
Ảnh upload bỏ dở và ảnh không còn được tham chiếu chưa có job dọn.

Kiểm tự động trên MinIO container riêng (không dùng bucket local):

```bash
cd car-rental-api
./mvnw -Dtest=MinioDocumentStorageIntegrationTest test
```

Mong đợi `BUILD SUCCESS`, không lỗi test. Bộ test dùng image `MINIO_IMAGE` từ `.env.example`,
account giả và script khởi tạo thật; Docker phải cho phép Testcontainers tạo container.
