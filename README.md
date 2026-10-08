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

### Xem SQL và thời gian chạy ở local

Theo security-guideline §3, **chỉ bật với dữ liệu local giả**. SQL có giá trị tham số có thể chứa
thông tin nhạy cảm; không chia sẻ log chưa kiểm tra, không bật profile này ở môi trường có dữ liệu thật.
Trước khi triển khai môi trường thật phải bổ sung chốt triển khai cấm profile `sql-log` ở đó.

Mặc định `decorator.datasource.enabled=false`: ứng dụng và suite test dùng Hikari trực tiếp,
**không đi qua proxy P6Spy**, không chỉ đơn thuần ẩn log. Không cần sửa `.env` để bật hoặc tắt.

Sau khi nạp biến môi trường theo hướng dẫn bên trên, chạy từ `car-rental-api/`:

```bash
./mvnw -Dspring-boot.run.profiles=sql-log spring-boot:run
```

Trong IntelliJ: **Run → Edit Configurations → cấu hình backend → Program arguments**, thêm
`--spring.profiles.active=sql-log`, rồi khởi động lại. Không chạy đồng thời hai backend trên cổng 8081.

Khi gọi một API có truy vấn CSDL, logger `p6spy` phải in từng câu đã chạy với thời gian ms và giá trị
tham số thay cho dấu `?`. Ví dụ minh họa (thời gian thực tế thay đổi):

```text
INFO ... [requestId=local-check-123] p6spy : SQL (2 ms):
-- Tra tham chiếu nội bộ theo ADR-0008; không JOIN sang schema vehicle.
SELECT
    b.id,
    b.code,
    b.name,
    b.address,
    public.ST_Y(CAST(b.location AS public.geometry)) AS latitude,
    public.ST_X(CAST(b.location AS public.geometry)) AS longitude
FROM branch.branch AS b
WHERE b.id = 42;
```

Giữ nguyên SQL nhiều dòng: dòng comment `--` phải kết thúc trước phần lệnh. Không dùng
`sqlSingleLine`. Đây là bản hiển thị phục vụ debug; câu thực thi vẫn dùng JDBC bind parameters,
không ghép chuỗi SQL trong code nghiệp vụ. Thời gian là thời gian thực thi JDBC do P6Spy đo,
không phải tổng thời gian HTTP hay toàn bộ transaction. Không đặt ngưỡng bỏ qua câu chạy nhanh.

Tắt bằng cách bỏ profile khỏi cấu hình IntelliJ hoặc chạy lại `./mvnw spring-boot:run`
không kèm profile (đồng thời bảo đảm không đặt `SPRING_PROFILES_ACTIVE=sql-log` bên ngoài).
Khởi động lại là bắt buộc; lượt chạy sau phải không có log SQL từ `p6spy`.

Để quan sát bằng Postman, import `postman/task-logging.postman_collection.json`, chọn environment
`Car Rental - Local`, rồi chạy thư mục **Part 1 - SQL logging** theo thứ tự 01 → 02, một iteration.
Request 01 tạo chi nhánh giả (201); request 02 đọc lại (200). Tất cả `pm.test` phải xanh.
Collection tự truyền mã và tạo tên mới mỗi lượt. Console backend bật profile phải có INSERT và
SELECT đã thay tham số, kèm ms; tắt profile và chạy lại thì API vẫn đúng nhưng không có SQL log.
Postman chỉ kiểm response API; phần log phải quan sát trong console, không đưa SQL vào response.

Kiểm mặc định không proxy, không log, và rollback thật trong PostgreSQL Testcontainers riêng:

```bash
./mvnw -Dtest=SqlLoggingIntegrationTest test
```

Chỉ khi chủ đích kiểm tương thích profile với dữ liệu giả trong Testcontainers mới chạy:

```bash
./mvnw -Dspring.profiles.active=sql-log '-Dtest=SqlLoggingIntegrationTest,ReservationWriteAdapterIntegrationTest,ReservationHoldIntegrationTest,ReservationDeadlockIntegrationTest,ReservationInsertRetryTest,ReservationHoldConcurrencyIntegrationTest' test
```

Lượt có profile phải vẫn nhận đúng SQLSTATE `23P01` và tên constraint `reservation_no_overlap`,
trả `VEHICLE_NOT_AVAILABLE` cho xe bận, retry deadlock `40P01` tối đa ba lần và không mất rollback
savepoint. Test 50 lượt giữ chỗ đồng thời phải có đúng một commit. `ReservationInsertRetryTest`
là unit test dùng mock; bằng chứng lỗi đi xuyên proxy đến từ các integration test còn lại.
Các context dùng `PostgresTestConfiguration` tự kiểm datasource thật đúng mode trước khi chạy.
Không bật profile SQL log cho lệnh `clean verify` thông thường.

Hai test SQL mới chạy được ở cả hai mode, không tự bật profile trong annotation:

| Test trong SqlLoggingIntegrationTest | Điều được chứng minh |
|---|---|
| logsEveryExecutionWithExpandedValuesAndPreservedNewlinesOnlyWhenEnabled | Mặc định không có sự kiện SQL; bật profile thì mỗi lần chạy có ms, tham số đã thay và giữ xuống dòng sau comment |
| preservesApplicationTransactionAndRollback | Thao tác ghi ứng dụng vẫn rollback thật; chỉ có sự kiện P6Spy khi bật profile |

`PostgresTestConfiguration` còn kiểm loại datasource ngay khi context khởi động: không profile phải
là Hikari trực tiếp; có `sql-log` phải thực sự là P6Spy bọc pool Hikari. Đây là chốt chống test xanh
giả khi chỉ đổi mức log nhưng chưa bật/tắt proxy.

### Theo dõi request bằng requestId

Không cần bật profile: mọi request đi qua filter được gắn MDC `requestId`, trả về header
`X-Request-Id`. Dùng `logging.pattern.correlation` có sẵn của Spring Boot để gắn tiền tố
`[requestId=...]` cho **mỗi sự kiện log** trong lượt xử lý HTTP, kể cả P6Spy khi bật `sql-log`.
Không tự tạo log cho request vốn không phát sinh sự kiện nào. Không thêm tiền tố vào từng dòng
SQL/stack trace: giữ nguyên dấu xuống dòng và định dạng IntelliJ bấm được tới code.
Log khởi động hoặc công việc ngoài request chưa có MDC sẽ hiện `[requestId=]`.

Client gửi đúng một `X-Request-Id` khớp `[A-Za-z0-9-]{1,64}` thì server dùng lại. Thiếu, quá dài,
nhiều giá trị hoặc có ký tự cấm thì sinh UUID mới. Không trim, không log header bị loại. ID không
phải token xác thực hoặc idempotency key; JSON và mã lỗi nghiệp vụ không đổi.

Filter giữ ID qua error/async dispatch và khôi phục MDC trong `finally`, tránh lẫn giữa request khi
thread được tái sử dụng. Phạm vi này không tự truyền MDC vào thread/`@Async` do code tự tạo;
khi thêm xử lý như vậy cần truyền ngữ cảnh riêng. Lỗi giao thức bị server từ chối trước filter
không bảo đảm có header này.

Sau khi khởi động lại backend, import lại `postman/task-logging.postman_collection.json`, chọn
`Car Rental - Local`, chạy thư mục **Part 2 - Request ID** (không cần chạy Part 1 trước):

1. Không gửi ID → health 200, response có UUID mới.
2. Gửi ID hợp lệ ngẫu nhiên → health 200, nhận lại đúng ID.
3. Gửi ký tự cấm `_` → health 200, nhận UUID mới.
4. Gửi ID 65 ký tự → API tìm xe thiếu tham số vẫn trả 400 `INVALID_REQUEST`, nhận UUID mới.

Mỗi request có `pm.test`; tất cả phải xanh. Không dùng Postman để gửi CR/LF thật: HTTP client/server
có thể chặn trước filter. `RequestIdFilterTest` kiểm CR/LF thật cùng biên 1/64/65 ký tự, nhiều header,
exception, hai request đồng thời, thread tái sử dụng và error/async dispatch.

Muốn đối chiếu SQL bằng mắt: bật `sql-log` với dữ liệu giả, chạy lại **Part 1 - SQL logging**, xem
header `X-Request-Id` của từng response và tìm đúng ID đó trong console backend. Sự kiện P6Spy
của request đó phải có cùng ID trước phần SQL nhiều dòng.

Chạy test từ `car-rental-api/` (Docker Desktop đang hoạt động; dùng PostgreSQL Testcontainers riêng):

```bash
./mvnw '-Dtest=RequestIdFilterTest,RequestLoggingConfigurationTest,RequestIdApiIntegrationTest,ApiExceptionHandlerTest,ArchitectureRulesTest' test
```

`RequestIdApiIntegrationTest` kiểm HTTP thật, tiền tố log đã render bởi Boot và lỗi 500 vẫn giấu
chi tiết; controller dò chỉ nằm trong test, không thêm endpoint vào backend thật.

Các test mới của phần requestId (mỗi dòng là một phương thức test; test tham số chạy nhiều trường hợp):

| Lớp | Test | Điều được chứng minh |
|---|---|---|
| RequestIdFilterTest | generatesIdWhenHeaderIsMissing | Sinh UUID, chain chạy đúng một lần, dọn MDC |
| RequestIdFilterTest | preservesSafeId | Giữ nguyên ID hợp lệ, gồm biên 1 và 64 ký tự |
| RequestIdFilterTest | replacesUnsafeId | Loại CR/LF thật, khoảng trắng, Unicode, ký tự cấm và ID 65 ký tự |
| RequestIdFilterTest | replacesMultipleHeaderValues | Nhiều giá trị header được thay bằng một UUID |
| RequestIdFilterTest | restoresPreviousContextAfterSuccess | Giữ ngữ cảnh bên ngoài và khóa MDC khác |
| RequestIdFilterTest | clearsIdAndPreservesExceptionWhenChainFails | Dọn MDC và ném lại đúng exception gốc |
| RequestIdFilterTest | restoresPreviousIdWhenChainFails | Nhánh lỗi khôi phục ID có từ trước |
| RequestIdFilterTest | generatesSeparateIdsOnReusedThread | Hai request nối tiếp không dùng lẫn ID |
| RequestIdFilterTest | preservesIdAcrossRedispatch | Error/async dispatch trên thread khác giữ ID ban đầu |
| RequestIdFilterTest | preservesIdDuringNestedErrorDispatch | Error lồng nhau khôi phục header sau reset response |
| RequestIdFilterTest | isolatesConcurrentRequests | Hai request đồng thời có MDC độc lập |
| RequestLoggingConfigurationTest | registersExactlyOneEarlyFilterForAllRequestPaths | Filter được đăng ký một lần, đúng thứ tự/path/dispatch |
| RequestIdApiIntegrationTest | correlatesEveryLogEventAndPreservesResponseShape | Hai logger qua HTTP thật đều có ID, JSON không đổi |
| RequestIdApiIntegrationTest | returnsGeneratedIdWhenHeaderIsMissing | Health trả UUID khi thiếu header |
| RequestIdApiIntegrationTest | replacesUnsafeHeaderOverHttp | HTTP thật thay ID sai hoặc quá dài, vẫn thành công |
| RequestIdApiIntegrationTest | correlatesUnexpectedFailureWithoutLeakingInternalDetails | Lỗi 500 có ID ở header/log, JSON không lộ nội bộ, stack trace giữ định dạng |

### Đọc tóm tắt lỗi máy chủ và job định kỳ

Lỗi HTTP 500 có **một sự kiện ERROR**: thông điệp là một dòng tóm tắt, ngay sau đó là stack trace
nguyên bản của exception đã nhận (gồm wrapper/cause). Không bật `%L`/`%M` và không sửa dòng `at ...`.
Các trường của dòng tóm tắt:

- `rootType`, `rootMessage`: loại lỗi và thông điệp của nguyên nhân sâu nhất.
- `source`: frame đầu tiên thuộc `com.carrental.` trong stack của **nguyên nhân gốc**, dạng
  `com.carrental.SomeClass.someMethod(SomeClass.java:42)`. Không có thì `-`, không lấy frame wrapper thay thế.
- `method`, `path`: HTTP method và URI path **không có query string**.
- `requestId`: ID đang nằm trong MDC, trùng header của response.

CR/LF, tab, ký tự điều khiển và ngắt dòng Unicode được escape **trong dòng tóm tắt**. Stack trace và
exception không bị thay đổi. Không dump body, token hoặc các header của request. Thông điệp exception
vẫn là dữ liệu chẩn đoán nội bộ: không chia sẻ log chưa kiểm tra dữ liệu nhạy cảm.
Response 500 giữ nguyên `INTERNAL_ERROR` với thông điệp chung, không thêm class, số dòng hay SQL.
Lỗi nghiệp vụ 4xx không được ghi thành lỗi máy chủ.

Scheduler hiện tại dùng `ThreadPoolTaskScheduler` do Boot tạo. Customizer chỉ thêm decorator và
ErrorHandler, không đổi số thread, nhịp hoặc độ trễ đầu. Mỗi lần chạy sinh `job-<UUID>` mới trong MDC;
ID bao cả use case, SQL và ErrorHandler. Cuối lượt luôn khôi phục MDC. Không dùng ID của thời điểm
đăng ký lịch cho mọi lần chạy.

Khi job lỗi, `method`/`path` là `-`. Lỗi vẫn đi ra khỏi proxy transaction để rollback, sau đó
ErrorHandler ghi **một** tóm tắt kèm stack trace và giữ hành vi chạy tiếp lượt định kỳ sau của Spring.
Không thêm retry cho toàn job, không sửa service hoặc SQL của BR-103. Không ghi lỗi lần nữa ở adapter.
Nếu sau này đổi sang scheduler tự tạo hoặc virtual-thread scheduler thì cần gắn và kiểm lại hook
tương ứng; customizer hiện tại dành cho scheduler pool đang dùng.

Kiểm Phần 3 từ `car-rental-api/`:

```bash
./mvnw '-Dtest=FailureSummaryTest,JobLoggingTaskDecoratorTest,SchedulerLoggingConfigurationTest,ApiFailureLoggingTest,ApiExceptionHandlerTest,RequestIdApiIntegrationTest,ReservationHoldExpirySchedulerTest,ReservationExpiryIntegrationTest,ArchitectureRulesTest' test
```

Kết quả đúng: `BUILD SUCCESS`, không failure/error. Các ca lỗi chủ đích **có thể in ERROR/stack trace**
trong console trong khi test vẫn xanh; đọc kết quả tổng kết Maven, không coi riêng màu đỏ của log là test fail.
Test PostgreSQL chạy bằng Testcontainers riêng, không làm hỏng CSDL local để thử lỗi.

| Lớp | Test mới hoặc được tăng kiểm chứng | Điều được chứng minh |
|---|---|---|
| FailureSummaryTest | selectsRootCauseAndItsFirstApplicationFrame | Lấy cause sâu nhất và frame đầu của chính cause |
| FailureSummaryTest | marksMissingRootFrameMessageAndContextExplicitly | Thiếu dữ liệu thì ghi `-`, không mượn frame wrapper |
| FailureSummaryTest | escapesControlCharactersWithoutMutatingThrowable | Tóm tắt chỉ một dòng, exception/stack không bị sửa |
| FailureSummaryTest | terminatesForCyclicCauseChain | Cause có vòng lặp không làm treo handler |
| FailureSummaryTest | handlesFramesWithoutSourceMetadata | Frame thiếu source hoặc native được xử lý rõ ràng |
| JobLoggingTaskDecoratorTest | generatesNewIdForEveryInvocationOfSameCallback | Một callback lặp lại có ID khác nhau mỗi lượt |
| JobLoggingTaskDecoratorTest | restoresOuterContextAfterSuccess | Không dùng lẫn ID HTTP và trả lại MDC ngoài job |
| JobLoggingTaskDecoratorTest | restoresContextAndPropagatesOriginalFailure | Decorator không nuốt lỗi hoặc làm rò MDC khi lỗi |
| ApiFailureLoggingTest | logsOneSummaryWithOriginalThrowableAndNoRequestPayload | HTTP lỗi ghi một lần, đúng root/path/ID, không dump dữ liệu request |
| ApiFailureLoggingTest | doesNotLogBusinessFailureAsServerError | Lỗi 400 không bị ghi ERROR hay đổi hợp đồng |
| SchedulerLoggingConfigurationTest | bootSchedulerLogsFailureOnceAndContinuesWithNewId | Hook Boot thật: một lỗi, cùng ID trong log, lượt sau vẫn chạy |
| ReservationHoldExpirySchedulerTest | realSchedulerContinuesAfterFailedInvocation | Job availability thật dùng wiring Boot, nhận ID mới và chỉ ghi lỗi một lần |

Không thêm endpoint gây lỗi 500 hoặc sửa CSDL để phục vụ Postman. Ca 500 và lỗi job được tạo có chủ
đích trong test. Muốn quan sát ID job ở local: bật `sql-log` với dữ liệu giả, đợi đến nhịp dọn giữ chỗ
(mặc định 30 giây); tìm câu `UPDATE availability.reservation` có điều kiện `hold_expires_at`.
Sự kiện đó phải có tiền tố `[requestId=job-...]`, và lượt sau có ID khác. Log in nội dung SQL,
không in tên file `release_expired_holds.sql`. Tắt profile khi không cần xem SQL.

### Nghiệm thu tổng thay đổi logging

Sau khi các phần riêng đã xanh, chạy toàn bộ suite từ `car-rental-api/`:

```bash
./mvnw clean verify
```

Docker Desktop phải đang hoạt động. Không thêm `-Dspring.profiles.active=sql-log` và bảo đảm terminal
không có `SPRING_PROFILES_ACTIVE=sql-log`; lượt nghiệm thu mặc định phải không đi qua proxy SQL.
`clean` chỉ dọn output build, không xóa volume PostgreSQL local. Không cần `docker compose down -v`.
Kết quả đúng là `BUILD SUCCESS`, `Failures: 0`, `Errors: 0`, `Skipped: 0`; ghi lại số test và thời gian
tổng từ Maven để bàn giao review. Không coi dòng ERROR do test cố tình gây lỗi là build thất bại.

Checklist bằng mắt (không cần làm lại phần đã kiểm và không thay đổi):

- Collection `postman/task-logging.postman_collection.json`, environment `Car Rental - Local`:
  **Part 1 - SQL logging** có hai request, **Part 2 - Request ID** có bốn request; mọi `pm.test` xanh.
- Bật profile với dữ liệu giả: SQL nhiều dòng có giá trị tham số, ms và ID trùng response header;
  SQL của job có `job-<UUID>` khác nhau qua mỗi lượt.
- Tắt profile rồi khởi động lại: API vẫn đúng, không còn sự kiện SQL từ P6Spy; requestId vẫn hoạt động.
- Lỗi 500, CR/LF thật và job lỗi đã được kiểm bằng test; không thêm endpoint lỗi hoặc phá CSDL để thử.
- `git status --short` không đưa `.env`, `.DS_Store`, log trong `target/` hoặc dữ liệu cá nhân vào commit.

Giới hạn đã biết: log SQL chỉ dành cho local giả; trước môi trường có dữ liệu thật phải có chốt triển
khai cấm profile này. MDC của HTTP không tự truyền sang thread tùy ý; scheduler custom/virtual cần
hook tương ứng nếu được đưa vào sau. Những việc đó không thuộc thay đổi logging local này.

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
