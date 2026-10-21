# car-rental

Nền tảng cho thuê xe hơi — marketplace P2P **cộng** đội xe của chính công ty.

## Trạng thái

Số liệu dự án — số quy tắc đã chốt, module đã xong, số test — ghi **duy nhất** ở mục "Trạng thái" của
[`CLAUDE.md`](CLAUDE.md). README không chép lại: hai nơi cùng ghi một con số thì sớm muộn sẽ lệch.

## Cấu trúc dự kiến

```text
car-rental/
├── CLAUDE.md              contract cho Claude (Chief Architect)
├── AGENTS.md              contract cho codex (Principal Engineer)
├── car-rental-docs/       tài liệu — nguồn sự thật duy nhất
├── car-rental-api/        backend Spring Boot
├── car-rental-keycloak/   provider OTP, Java 21, nạp vào Keycloak local
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

**Bảo hiểm bắt buộc khai báo (BR-231):** đặt `CAR_RENTAL_INSURANCE_CALCULATION` và
`CAR_RENTAL_INSURANCE_VALUE` trước khi khởi động backend, kể cả khi chạy từ IntelliJ.
`.env.example` để trống hai giá trị vì chưa có mức phí thật được chốt.

- `PER_BILLING_DAY`: giá trị là số nguyên VND > 0, nhân số ngày tính tiền; gói giờ luôn một ngày.
- `PERCENT_OF_RENT`: giá trị là tỷ lệ thập phân > 0; `0.1` nghĩa là 10%, không đặt trần 100%.

Để thử local bằng dữ liệu giả, bạn có thể tự đặt trong `.env`:

```dotenv
CAR_RENTAL_INSURANCE_CALCULATION=PER_BILLING_DAY
CAR_RENTAL_INSURANCE_VALUE=100000
```

100.000 VND ở đây chỉ là dữ liệu thử, không phải giá bảo hiểm thật hay mặc định của ứng dụng.
Thiếu/rỗng, giá 0/âm hoặc phí cố định có phần lẻ làm startup thất bại, log nêu tên
`car-rental.pricing.insurance.calculation` hoặc `car-rental.pricing.insurance.value`.
Mức tăng cuối tuần/lễ dùng dạng thập phân, không âm; hạn báo giá phải dương.
Nguồn tham số hiện là cấu hình ứng dụng, đổi cần khởi động lại; lịch lễ nằm trong YAML,
không tự suy ngày nghỉ liền kề. Trước khi chạy với khách thật phải có module config (ADR-0013).

### 2. Khởi động hạ tầng

**Build provider trước khi chạy Keycloak** (bytecode Java 21, dùng được JDK 25 ở trên):

```bash
cd car-rental-keycloak
./mvnw clean verify
cd ..
```

Trong `.env`, bổ sung đủ khối **Keycloak OTP** từ `.env.example`. Giữ các tham số BR-129 theo mẫu.
Sáu biến secret đang để trống phải được điền riêng; không lấy secret công khai của test:

- `CAR_RENTAL_OTP_HMAC_SECRET`: dùng `openssl rand -base64 32`; giữ nguyên qua restart để không đổi
  khoá HMAC của dữ liệu OTP/hạn mức đã lưu.
- `CAR_RENTAL_KEYCLOAK_READER_SECRET`: dùng một giá trị riêng từ `openssl rand -hex 32`.
- Bốn biến `CAR_RENTAL_LOCAL_*_PASSWORD`: mỗi biến dùng một giá trị riêng từ `openssl rand -hex 16`.
  Đây là mật khẩu tạm, tối thiểu 12 ký tự, phải đổi sau mật khẩu + SMS ở lần đăng nhập đầu (BR-809).

Chỉ chạy các lệnh sinh secret trên máy mình, không dán kết quả vào chat/log chia sẻ hoặc commit.
`CAR_RENTAL_TERMS_VERSION=local-test-v1` chỉ định văn bản **thử**, không phải điều khoản dùng cho khách thật.

```bash
docker compose config --quiet
docker compose up -d
docker compose ps
```

PostgreSQL, Keycloak và MinIO phải cùng hiện trạng thái `(healthy)`.
`config --quiet` kiểm cấu hình mà không in giá trị secret; thiếu biến sẽ báo đúng tên biến.

Compose chỉ mount provider lõi và JAR SMS local, không mount JAR `*-tests.jar`. Nếu chưa build,
mount phải báo thiếu file, không tự tạo thư mục giả mang tên `.jar`. Không đổi image/platform.

Realm `car-rental` được import từ `infra/keycloak/realm-export.json`; các bảng OTP được Liquibase
của Keycloak tạo trong **database `keycloak`**, không thuộc Flyway/backend. Import lúc khởi động
[bỏ qua realm đã tồn tại](https://www.keycloak.org/server/importExport#_importing_a_realm_during_startup):
restart không xoá user, không đặt lại mật khẩu và không áp lại nội dung JSON lên realm cũ.
Nếu local đã có realm `car-rental` từ cấu hình khác, dừng để đối chiếu trước; **không xoá realm hoặc volume**.

Khi build lại JAR hoặc đổi biến môi trường, từ gốc repo chạy `docker compose up -d --force-recreate keycloak`
để nạp lại đúng file mount/cấu hình. Không dùng `down -v`. `docker compose restart` không cập nhật biến
môi trường mới. Đổi các biến mật khẩu/secret import sau lần đầu không đổi credential đang lưu trong realm.

Thông tin các flow, tài khoản thử và cách kiểm provider: [Keycloak provider](car-rental-keycloak/README.md).
Backend xác minh access token của realm bằng chữ ký RS256, issuer, audience `car-rental-api` và hạn dùng.
Gửi token qua header `Authorization: Bearer <access-token>`; không truyền qua query, cookie hoặc form.
`/api/v1/public/**` và `GET /actuator/health` không cần token; `/api/v1/client/**` cần vai `CUSTOMER`;
`/api/v1/admin/**` cần vai nhân viên và token của client `admin-web` (mật khẩu + OTP).
Thiếu/token sai trả `401 UNAUTHENTICATED`; token hợp lệ nhưng sai quyền namespace trả `403 ACCESS_DENIED`.
Backend không lưu phiên đăng nhập. Tạo chi nhánh chỉ cho ADMIN, kiểm trong use case theo BR-810.
Đọc chi nhánh quản trị chỉ cho ADMIN (mọi chi nhánh) hoặc STAFF/MANAGER (chi nhánh mình);
nhân viên chưa gán chi nhánh nhận `403 STAFF_BRANCH_REQUIRED`, đọc chéo nhận `403 ACCESS_DENIED`.
Tạo xe chỉ cho ADMIN ở mọi chi nhánh hoặc STAFF/MANAGER ở chi nhánh mình;
STAFF gửi kèm giá trong yêu cầu hợp lệ nhận `403 ACCESS_DENIED`, không bỏ qua giá.
Gửi duyệt xe chỉ cho ADMIN ở mọi chi nhánh hoặc STAFF/MANAGER ở chi nhánh mình;
xe khác chi nhánh bị chặn trước kiểm trạng thái. Xe đúng quyền vẫn phải ở DRAFT để gửi duyệt.
Duyệt xe chỉ cho ADMIN hoặc MANAGER của chi nhánh có xe; STAFF không được duyệt.
Người đủ quyền vẫn phải đáp ứng trạng thái chờ duyệt, giấy tờ còn hạn và đã có giá ngày.
Đọc xe quản trị chỉ cho ADMIN (mọi xe) hoặc STAFF/MANAGER (xe của chi nhánh mình);
chưa gán chi nhánh nhận `403 STAFF_BRANCH_REQUIRED`, đọc chéo hoặc sai vai nhận `403 ACCESS_DENIED`.
Đổi giá ngày chỉ cho ADMIN hoặc MANAGER của chi nhánh có xe; STAFF không được đổi giá.
Giá phải dương, nguyên VND và chia hết cho 1.000; cập nhật không đổi trạng thái hay báo giá cũ.
Chỉ dùng dữ liệu giả ở local, không dùng bản này với dữ liệu thật.
Báo giá hiện chưa gắn chủ sở hữu.
Kiểm bằng thư mục `Part 3 - Backend namespace security` trong
[collection identity](postman/task-08a-identity.postman_collection.json), environment `Car Rental - Local`.
Các request OAuth2 dùng **Get New Access Token → Use Token**, không copy token vào file hay bật đồng bộ token.

Đọc tài khoản của chính mình bằng `GET /api/v1/client/accounts/me` (khách) hoặc
`GET /api/v1/admin/accounts/me` (nhân viên); hợp đồng ở [API tài khoản](car-rental-docs/vi/api/identity.md).
Thư mục `Part 3 - Current account` trong collection identity tự lưu mã khách và nhân viên vào biến
collection. Nhân viên chưa gán chi nhánh vẫn lấy được mã `TK-<6>` qua `/me`; đây không phải quyền
thao tác chi nhánh. Chỉ dùng dữ liệu thử, không export token hoặc response chứa thông tin thật vào Git.

ADMIN gán chi nhánh bằng `PUT /api/v1/admin/accounts/{accountCode}/branch`, body chứa `branchCode`.
Thư mục `Part 3 - Staff branch assignment` trong collection lấy mã STAFF qua `/me`, tạo chi nhánh
thử rồi gán. Chọn đúng token ADMIN/STAFF theo mô tả từng request. Chạy lại thư mục này tạo chi nhánh
thử mới và thay bản gán của nhân viên thử; vai đích được đọc từ Keycloak, không gửi trong body.

Backend tra vai hiện tại bằng client chỉ đọc `identity-reader`. Bổ sung vào `.env`:

```dotenv
CAR_RENTAL_KEYCLOAK_BASE_URL=http://localhost:8080
CAR_RENTAL_KEYCLOAK_REALM=car-rental
CAR_RENTAL_KEYCLOAK_READER_CLIENT_ID=identity-reader
```

Cổng trong URL phải khớp `KEYCLOAK_PORT`. Backend dùng lại `CAR_RENTAL_KEYCLOAK_READER_SECRET`
đã khai báo ở trên; phải khớp credential đang lưu trong realm, không sinh lại mỗi lần chạy.
Thiếu cấu hình làm backend dừng ngay khi khởi động; lỗi kết nối/quyền client lúc đọc vai không được
coi như người dùng không tồn tại. Kết nối có timeout, không đi theo redirect, không cache vai.

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

### Log

Theo [security-guideline §3](car-rental-docs/vi/architecture/security-guideline.md#3-dữ-liệu-nhạy-cảm),
**SQL kèm tham số chỉ được bật với dữ liệu local giả**, không ở môi trường có dữ liệu thật.
Không chia sẻ log chưa kiểm tra thông tin nhạy cảm; môi trường thật cần chốt cấm profile `sql-log`.

**Bật/tắt SQL log**

Mặc định app và test không bọc datasource bằng P6Spy, không có SQL log/caller.
Nạp biến môi trường theo hướng dẫn chạy local bên trên, rồi từ `car-rental-api/` bật riêng cho app:

```bash
./mvnw -Dspring-boot.run.profiles=sql-log spring-boot:run
```

IntelliJ: **Run → Edit Configurations → cấu hình backend → Program arguments**, thêm
`--spring.profiles.active=sql-log`, rồi khởi động lại. Không chạy hai backend cùng cổng.
Tắt: bỏ argument đó, hoặc dừng app dòng lệnh rồi chạy `./mvnw spring-boot:run` không kèm profile.
**Không đặt `SPRING_PROFILES_ACTIVE=sql-log` trong shell hay `.env`**: chạy `./mvnw clean verify`
từ shell đã nạp biến đó sẽ đưa cả bộ test qua proxy. Nếu đã export, bỏ cấu hình nguồn và
`unset SPRING_PROFILES_ACTIVE` trước khi chạy test thông thường; khởi động lại app để đổi mode.

**Tìm request bằng requestId và api**

Lấy `X-Request-Id` từ response rồi tìm ID đó trong console IntelliJ. Mỗi sự kiện log của request
có tiền tố như dưới, dù không bật `sql-log`; request không phát log thì không có dòng để tìm:

```text
[requestId=local-check-123] [api=GET /api/v1/admin/branches/CN-ABC123]
```

Server dùng lại đúng một ID client khớp `[A-Za-z0-9-]{1,64}`; thiếu/không hợp lệ thì sinh UUID.
`api` chỉ gồm method + path, không query string, body hay header; control characters được escape.
Nhãn MDC tối đa 512 đơn vị UTF-16 sau escape, gồm `[truncated]` nếu bị cắt; không cắt dở
Unicode/escape, không đổi URI thật. Redispatch giữ ID/nhãn ban đầu; MDC không tự truyền sang thread tự tạo.
Tiền tố áp cho mỗi **sự kiện**, không lặp trên từng dòng SQL/stack trace. Ngoài context, cụm rỗng được ẩn.

**Đọc SQL và caller**

Khi bật `sql-log`, P6Spy in SQL đã thay tham số, thời gian JDBC (không phải tổng thời gian HTTP)
và caller trong → ngoài. Ví dụ minh họa dưới lược timestamp/PID/thread; ID, ms và số dòng có thể khác:

```text
INFO [requestId=local-check-123] [api=GET /api/v1/admin/branches/CN-ABC123] p6spy : SQL (2 ms):
caller: branch.adapter.out.persistence.BranchReadAdapter.findByCode(BranchReadAdapter.java:75) ← branch.application.service.BranchQueryService.get(BranchQueryService.java:40) ← branch.adapter.in.rest.admin.AdminBranchController.get(AdminBranchController.java:117)
-- Đọc chi tiết chi nhánh theo mã nghiệp vụ.
-- Tách vị trí thành vĩ độ và kinh độ để ánh xạ sang BranchDetail.
SELECT
    b.id,
    b.code,
    b.name,
    b.address,
    public.ST_Y(CAST(b.location AS public.geometry)) AS latitude,
    public.ST_X(CAST(b.location AS public.geometry)) AS longitude
FROM branch.branch AS b
WHERE b.code = 'CN-ABC123';
```

Caller giữ package/module, chỉ bỏ `com.carrental.`; tối đa 12 frame, dư có `← [truncated]`.
Không có frame ứng dụng thì `caller: -`; loại proxy/CGLIB và tầng log. Hai service cùng tên
được phân biệt bằng module. SQL giữ xuống dòng sau `--`; không dùng `sqlSingleLine`.
Đây chỉ là hiển thị: JDBC vẫn bind tham số, không nối SQL nghiệp vụ. Giới hạn `api` không cắt SQL.

**Đọc lỗi HTTP 500 và lỗi job**

Mỗi lỗi có một sự kiện ERROR: `Failure summary:` đứng trước stack trace nguyên bản. Ví dụ giả lập
lỗi kết nối (lược timestamp/PID/thread/logger; không phải lỗi hiện tại của hệ thống):

```text
ERROR [requestId=local-check-123] [api=GET /api/v1/admin/branches/CN-ABC123] Failure summary: rootType="java.net.ConnectException" rootMessage="Connection refused" source="-" method="GET" path="/api/v1/admin/branches/CN-ABC123" requestId="local-check-123" api="GET /api/v1/admin/branches/CN-ABC123"
```

`rootType/rootMessage` mô tả nguyên nhân sâu nhất; `source` là frame `com.carrental` đầu tiên của
chính nguyên nhân đó, dạng `Class.method(File.java:dòng)`, không có thì `-`. `method/path` không có query.
Ký tự điều khiển trong tóm tắt được escape; stack trace không đổi để IntelliJ bấm tới code.
Response 500 chỉ có `INTERNAL_ERROR` và thông điệp chung, không lộ class, số dòng hay SQL.

Job `@Scheduled` có `requestId=job-<UUID>` mới mỗi lượt và `api=Class.method` trong cả SQL lẫn log lỗi.
Khi job lỗi, `method/path` là `-`; vẫn ghi stack trace và tiếp tục lượt định kỳ sau, không ghi lỗi hai lần:

```text
ERROR [requestId=job-7ea10e02-1f17-443a-bfa2-dc228d25d847] [api=ReservationHoldExpiryScheduler.sweep] Failure summary: rootType="java.net.ConnectException" rootMessage="Connection refused" source="-" method="-" path="-" requestId="job-7ea10e02-1f17-443a-bfa2-dc228d25d847" api="ReservationHoldExpiryScheduler.sweep"
```

**Quan sát và kiểm tra**

Postman: import [SQL Caller](postman/task-sql-caller.postman_collection.json), chọn [Car Rental - Local](postman/local.postman_environment.json); collection có mô tả và `pm.test`, đối chiếu log bằng ID response.
Kiểm profile với dữ liệu giả trong PostgreSQL Testcontainers (Docker Desktop hoạt động), từ `car-rental-api/`:

```bash
./mvnw -Dspring.profiles.active=sql-log '-Dtest=SqlLoggingIntegrationTest,SqlCallerFlowIntegrationTest,ReservationHoldExpirySchedulerTest,ReservationWriteAdapterIntegrationTest,ReservationHoldIntegrationTest,ReservationDeadlockIntegrationTest,ReservationInsertRetryTest,ReservationHoldConcurrencyIntegrationTest' test
```

Kỳ vọng `BUILD SUCCESS`, không failure/error. Ca cố tình gây lỗi có thể in ERROR/stack trace dù test xanh.
Suite thông thường dùng `./mvnw clean verify` **không bật profile**; không cần xóa volume local.

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

Lát cắt dọc đầu tiên: **tìm xe → báo giá → đặt xe → giữ chỗ → trả cọc**. Đã xong tìm xe và cơ chế giữ
chỗ. Thứ tự còn lại theo [ADR-0012](car-rental-docs/vi/decisions/adr-0012-vertical-slice-delivery.md)
(mục "Làm rõ 08/10/2026"): chốt BR-218 → `pricing` → `identity` → `booking` → `payment` →
`customer-web`.
