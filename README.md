# car-rental

Nền tảng cho thuê xe hơi — marketplace P2P **cộng** đội xe của chính công ty.

## Trạng thái

| Hạng mục | Trạng thái |
|---|---|
| Nghiệp vụ giai đoạn 1 | ✅ 149 quy tắc BR đã chốt |
| Kiến trúc | ✅ 15 ADR · 10 guideline |
| Code | Đã có hạ tầng, khung backend, rule kiến trúc và code Task 4; chờ Tech Owner chạy nghiệm thu phần bổ sung |
| Môi trường | Local, chưa build production |

## Cấu trúc dự kiến

```text
car-rental/
├── CLAUDE.md              contract cho Claude (Chief Architect)
├── AGENTS.md              contract cho codex (Principal Engineer)
├── car-rental-docs/       tài liệu — nguồn sự thật duy nhất
├── car-rental-api/        backend Spring Boot
├── customer-web/          web khách thuê             (chưa tạo)
├── admin-web/             web vận hành               (chưa tạo)
├── mobile/                React Native               (chưa tạo)
└── infra/                 docker-compose cho local
```

## Bắt đầu từ đâu

1. [`CLAUDE.md`](CLAUDE.md) — quy tắc làm việc
2. [`car-rental-docs/README.md`](car-rental-docs/README.md) — chỉ mục và thứ tự đọc

## Ba điều quan trọng nhất cần biết

**Đây là hệ thống phân bổ tài nguyên vật lý không nhân bản được.** Trùng lịch được chặn bằng ràng
buộc của PostgreSQL, không bằng logic ứng dụng (ADR-0005).

**Đơn thuê đóng băng mọi giá trị tại thời điểm tạo.** Admin đổi cấu hình không được ảnh hưởng đơn
đã có (ADR-0014).

**Biên bản bàn giao là bằng chứng pháp lý, không phải giấy tờ hành chính.** Nó quyết định công ty
hay khách trả tiền phạt nguội (ADR-0015).

## Chạy local

### Yêu cầu

- Docker Desktop đang chạy.
- JDK 25 hoặc mới hơn.
- Không cần cài Maven vì repo đã có Maven Wrapper.

Các lệnh dưới đây được chạy từ thư mục gốc của repo.

### 1. Chuẩn bị cấu hình local

Nếu chưa có `.env`, tạo từ file mẫu:

```bash
test -f .env || cp .env.example .env
```

Mở `.env`, thay các giá trị mẫu bằng giá trị local của bạn. Không commit file `.env`.

### 2. Khởi động hạ tầng

```bash
docker compose up -d
docker compose ps
```

PostgreSQL, Keycloak và MinIO phải cùng hiện trạng thái `(healthy)`.

### 3. Nạp biến môi trường

```bash
set -a
. ./.env
set +a
```

`set -a` làm cho các biến đọc từ `.env` được export sang tiến trình Spring Boot. `set +a` đưa shell
trở lại chế độ bình thường.

### 4. Chạy backend

```bash
cd car-rental-api
./mvnw spring-boot:run
```

Backend sử dụng cổng `CAR_RENTAL_API_PORT` trong `.env`. Giá trị local mẫu là `8081`.

Mở terminal thứ hai và kiểm tra:

```bash
curl --fail --silent --show-error \
  http://127.0.0.1:8081/actuator/health
```

Ứng dụng đã sẵn sàng khi response có trạng thái tổng thể `UP` và component `db` cũng có trạng thái
`UP`.

Để dừng môi trường, nhấn `Ctrl+C` tại terminal chạy backend, sau đó từ thư mục gốc chạy:

```bash
docker compose down
```

Không thêm `--volumes` nếu muốn giữ dữ liệu PostgreSQL và MinIO.

### Chạy backend bằng IntelliJ IDEA

1. Khởi động hạ tầng bằng `docker compose up -d`.
2. Mở `car-rental-api/pom.xml` dưới dạng Maven project và chọn JDK 25 hoặc mới hơn.
3. Chạy class `com.carrental.CarRentalApplication`.
4. Mở **Run → Edit Configurations** và chọn cấu hình vừa tạo.
5. Đặt **Working directory** thành `$PROJECT_DIR$/car-rental-api`.
6. Đặt **Use classpath of module** thành `car-rental-api`.
7. Tại **Environment variables**, chọn **Browse for .env files and scripts**, rồi chọn
   `$PROJECT_DIR$/.env`.
8. Không bật **Store as project file** vì `.idea/` không được commit.
9. Chạy lại bằng **Run** hoặc **Debug**.

### Gọi lịch xe từ module khác

Tiêm `com.carrental.availability.api.AvailabilityDirectory` để giữ chỗ, khóa vận hành,
chuyển trạng thái hoặc tìm xe bận. Đây là cổng nội bộ, không có REST endpoint riêng.
Chỉ import các kiểu thuộc `availability.api`; không gọi trực tiếp use case hoặc persistence.

Khoảng truyền vào `hold` và `findBusyVehicleIds` chưa cộng đệm; truyền đệm riêng để availability
áp dụng đúng một lần. Bên gọi không chọn TTL. Các chuyển trạng thái dùng mã reservation `KL-<6>`,
không dùng mã đơn. Kết quả tra cứu rảnh không bảo đảm giữ được chỗ sau đó: PostgreSQL vẫn quyết
định xung đột khi ghi. Các thao tác ghi tham gia transaction bên gọi, không tự commit độc lập.

### Job nhả giữ chỗ quá hạn

Theo BR-103, backend tự chuyển `HELD` thành `RELEASED` khi `hold_expires_at` đã đến hạn.
Job luôn được đăng ký, không có công tắc tắt; dùng `applicationClock` chung và không tính lại TTL từ cấu hình hiện tại.
Chỉ `status` và `status_changed_at` được cập nhật; không xóa bản ghi lịch.

Nhịp chạy lấy từ `car-rental.availability.hold-sweep-interval`
(`CAR_RENTAL_HOLD_SWEEP_INTERVAL`, mặc định `PT30S`). Job đợi một nhịp sau khởi động;
sau mỗi lượt kết thúc mới đợi tiếp một nhịp, không chạy bù dồn các lượt bị chậm.
Nhịp phải lớn hơn không. Đây là cấu hình vận hành, không đi qua port chính sách TTL.

Hai instance cùng chạy được bảo vệ bởi điều kiện `status = 'HELD'` và
`hold_expires_at <= mốc_dọn` trong cùng câu `UPDATE`. Bản đã nhả hoặc đã xác nhận
không bị job ghi đè. Nếu một lượt gặp lỗi, transaction rollback, scheduler ghi nhận
lỗi và tiếp tục ở lượt định kỳ sau. Job chỉ nhả khóa lịch, không xử lý đơn thuê hay tiền.

Test đặt `car-rental.availability.hold-sweep-interval=PT24H`
trong `src/test/resources/application.properties`. Job vẫn được đăng ký, nhưng độ trễ ban đầu
cũng là 24 giờ nên không tự sửa fixture trong thời gian chạy suite thông thường.
File này không được đóng gói vào ứng dụng local. Các test expiry gọi use case chủ động;
`ReservationHoldExpirySchedulerTest` dùng context riêng để kiểm đăng ký job, nhịp chạy,
độ trễ ban đầu và khả năng tiếp tục sau lỗi. Cấu hình công tắc cũ không còn tác dụng.
Không thêm biến bắt buộc vào `.env`.

## Bước tiếp theo

Task 4: xem [hướng dẫn nghiệm thu shared, branch và vehicle](car-rental-docs/vi/dev-notes/task-04-shared-branch-vehicle.md).
Code và test bổ sung chưa được Codex chạy theo yêu cầu của Tech Owner.

Lát cắt dọc đầu tiên: **tìm xe → đặt xe → giữ chỗ → trả cọc** — chạm ngay vào rủi ro số một.
