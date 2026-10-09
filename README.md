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
INFO ... [requestId=local-check-123] [api=GET /api/v1/admin/branches/CN-ABC123] p6spy : SQL (2 ms):
caller: branch.adapter.out.persistence.BranchReadAdapter.findByCode(BranchReadAdapter.java:76) ← branch.application.service.BranchQueryService.get(BranchQueryService.java:40) ← branch.adapter.in.rest.admin.AdminBranchController.get(AdminBranchController.java:117)
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
`[requestId=...] [api=GET /path]` cho **mỗi sự kiện log** trong lượt xử lý HTTP, kể cả P6Spy khi bật `sql-log`.
Không tự tạo log cho request vốn không phát sinh sự kiện nào. Không thêm tiền tố vào từng dòng
SQL/stack trace: giữ nguyên dấu xuống dòng và định dạng IntelliJ bấm được tới code.
`api` chỉ gồm HTTP method và URI path, không có query string, body hoặc header. Ký tự điều khiển
được escape trước khi vào MDC. Error/async redispatch giữ nhãn API ban đầu, không đổi thành `/error`.
Cuối lượt filter khôi phục cả hai khóa MDC. Log ngoài request/job không có cả hai khóa sẽ ẩn toàn bộ
cụm correlation bằng `%replace` có sẵn của Logback, không dùng converter tự viết.

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
- `api`: HTTP method + path, hoặc tên `Class.method` của hàm `@Scheduled`; thiếu metadata thì `-`.
  Giá trị trong MDC tối đa 512 đơn vị UTF-16 sau escape, tính cả hậu tố `[truncated]` khi cần cắt.
  Không cắt giữa một code point hoặc escape; vì vậy kết quả có thể ngắn hơn 512. URI thật không đổi.

CR/LF, tab, ký tự điều khiển và ngắt dòng Unicode được escape **trong dòng tóm tắt**. Stack trace và
exception không bị thay đổi. Không dump body, token hoặc các header của request. Thông điệp exception
vẫn là dữ liệu chẩn đoán nội bộ: không chia sẻ log chưa kiểm tra dữ liệu nhạy cảm.
Response 500 giữ nguyên `INTERNAL_ERROR` với thông điệp chung, không thêm class, số dòng hay SQL.
Lỗi nghiệp vụ 4xx không được ghi thành lỗi máy chủ.

Scheduler do Spring Boot tự tạo; ứng dụng không khai báo bean `taskScheduler` hoặc lớp scheduler riêng.
`ThreadPoolTaskSchedulerCustomizer` gắn decorator và ErrorHandler vào scheduler pool của Boot.
Với `@Scheduled`, `ScheduledJobObservationHandler` lấy tên từ `ScheduledTaskObservationContext`
ngay khi method chạy, không phụ thuộc lớp bọc Runnable của Spring.
Hook dùng Observation/Actuator đã có trong ứng dụng, không cần profile `sql-log`, không thêm thư viện,
không dò stack hoặc field riêng của framework. Không đổi số thread, nhịp hoặc độ trễ đầu.
Mỗi lần chạy sinh `job-<UUID>` mới trong MDC, cùng `api=ReservationHoldExpiryScheduler.sweep`;
ngữ cảnh bao cả use case, SQL và ErrorHandler. Decorator ngoài cùng khôi phục cả hai khóa sau khi xử
lý lỗi xong. Không dùng ID của thời điểm đăng ký lịch cho mọi lần chạy. Runnable không có metadata
method dùng `api=-`; không lấy tên job từ stack exception hoặc kế thừa nhãn của lượt trước.
Handler chỉ cập nhật MDC trong phạm vi decorator sở hữu, không suy từ tiền tố `job-` mà client có
thể gửi. Không dọn `api` khi observation dừng: lúc đó ErrorHandler chưa chạy; decorator ngoài cùng
mới dọn sau bước ghi lỗi. Registry observation của scheduled task phải hoạt động; nếu thay cấu hình
Actuator/Observation hoặc tắt observation `tasks.scheduled.execution`, phải kiểm lại nhãn job.

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
Sự kiện đó phải có tiền tố `[requestId=job-...] [api=ReservationHoldExpiryScheduler.sweep]`,
và lượt sau có ID khác. Log in nội dung SQL,
không in tên file `release_expired_holds.sql`. Tắt profile khi không cần xem SQL.

### Mở rộng SQL caller — Phần 1: ngữ cảnh API và job

Phần này kiểm nhãn `api`; chuỗi caller kiểm riêng ở Phần 2 bên dưới. Thay đổi thuần kỹ thuật theo security-guideline
§3; không sửa endpoint, JSON response, migration, SQL hoặc hành vi dọn giữ chỗ BR-103.

Chạy từ `car-rental-api/`, Docker Desktop đang hoạt động. Không bật profile SQL cho lượt test này:

```bash
./mvnw '-Dtest=RequestIdFilterTest,RequestLoggingConfigurationTest,RequestIdApiIntegrationTest,FailureSummaryTest,ApiFailureLoggingTest,ApiExceptionHandlerTest,JobLoggingTaskDecoratorTest,ScheduledJobObservationHandlerTest,CorrelationPatternTest,SchedulerLoggingConfigurationTest,ReservationHoldExpirySchedulerTest,ArchitectureRulesTest' test
```

Kết quả đúng: `BUILD SUCCESS`, `Failures: 0, Errors: 0`. Các test chủ đích gây lỗi 500/job vẫn in
ERROR/stack trace: đó là dữ liệu kiểm chứng, không đồng nghĩa test thất bại. Không dùng CSDL local
để gây lỗi và không cần xóa volume. Các test mới hoặc được mở rộng:

| Lớp.test | Điều được chứng minh |
|---|---|
| RequestIdFilterTest.includesOnlyMethodAndPathInApiContext | Không đưa query chứa tọa độ, body hoặc token vào `api` |
| RequestIdFilterTest.escapesControlCharactersBeforePuttingApiInMdc | CR/LF, tab, ANSI và Unicode điều khiển không chèn được dòng giả |
| RequestIdFilterTest.restoresPreviousContextAfterSuccess | Khôi phục cả ID/API và khóa khác sau thành công |
| RequestIdFilterTest.clearsIdAndPreservesExceptionWhenChainFails | Lỗi chain giữ nguyên đối tượng, dọn cả ID/API |
| RequestIdFilterTest.restoresPreviousIdWhenChainFails | Lỗi không phá MDC của ngữ cảnh bao ngoài |
| RequestIdFilterTest.preservesIdAcrossRedispatch | Error/async trên thread khác giữ ID và API gốc |
| RequestIdFilterTest.preservesIdDuringNestedErrorDispatch | Error dispatch lồng nhau không đổi API gốc thành `/error` |
| RequestIdFilterTest.isolatesConcurrentRequests | Hai request cùng chạy không lẫn ID hoặc API trước/sau điểm đồng bộ |
| RequestIdApiIntegrationTest.correlatesEveryLogEventAndPreservesResponseShape | HTTP thật render ID/API qua hai logger, không log query marker, JSON không đổi |
| RequestIdApiIntegrationTest.correlatesUnexpectedFailureWithoutLeakingInternalDetails | 500 có ID/API, stack trace chuẩn, JSON không lộ nội bộ |
| FailureSummaryTest.selectsRootCauseAndItsFirstApplicationFrame | Định dạng mở rộng giữ nguyên root/frame và ghi API thiếu bằng `-` |
| FailureSummaryTest.marksMissingRootFrameMessageAndContextExplicitly | Context rỗng không tạo dữ liệu chẩn đoán giả |
| FailureSummaryTest.escapesControlCharactersWithoutMutatingThrowable | Cả API trong tóm tắt được escape, throwable không bị sửa |
| FailureSummaryTest.includesJobIdentityWithoutApplicationRootFrame | Tên job vẫn có dù root exception không có frame ứng dụng |
| ApiFailureLoggingTest.logsOneSummaryWithOriginalThrowableAndNoRequestPayload | Một sự kiện lỗi chứa ID/API, không có query/body/token/header trong summary hoặc MDC |
| JobLoggingTaskDecoratorTest.generatesNewIdForEveryInvocationOfSameCallback | Mỗi lượt có ID mới và không kế thừa API lượt trước |
| JobLoggingTaskDecoratorTest.restoresOuterContextAfterSuccess | Khôi phục cả hai khóa sau job thành công |
| JobLoggingTaskDecoratorTest.restoresContextAndPropagatesOriginalFailure | Khôi phục cả hai khóa khi lỗi, không nuốt exception |
| ScheduledJobObservationHandlerTest.supportsOnlyScheduledMethodContext | Không nhận nhầm observation HTTP/JDBC thành job |
| ScheduledJobObservationHandlerTest.ignoresMethodObservationOutsideManagedJob | ID client có tiền tố `job-` không làm mất nhãn HTTP |
| ScheduledJobObservationHandlerTest.retainsIdentityUntilDecoratorRestoresContextOnFailure | Observation dừng vẫn giữ tên cho log lỗi, decorator khôi phục context và dọn marker |
| ScheduledJobObservationHandlerTest.restoresNestedJobScopeAfterSuccess | Job lồng nhau khôi phục đúng phạm vi ngoài, không rò MDC/ThreadLocal sau hoàn tất |
| CorrelationPatternTest.rendersOnlyUsefulCorrelationContext | YAML thật ẩn cụm khi cả hai khóa trống; còn một khóa thì vẫn hiển thị |
| SchedulerLoggingConfigurationTest.bootSchedulerLogsFailureOnceAndContinuesWithNewId | Callback không có metadata dùng `api=-`, không ghi trùng lỗi và lượt sau vẫn chạy |
| ReservationHoldExpirySchedulerTest.realSchedulerContinuesAfterFailedInvocation | Job `@Scheduled` thật có tên `ReservationHoldExpiryScheduler.sweep` trong MDC và summary, lượt sau tiếp tục |

Context nhỏ của `ReservationHoldExpirySchedulerTest` nạp cả `ObservationAutoConfiguration` và
`ScheduledTasksObservationAutoConfiguration`, giống wiring Actuator của app thật; không gán tên job
bằng tay trong test. Giữ nguyên assertion của ca từng đỏ `api=-`, không hạ kỳ vọng hoặc bỏ test.

Kiểm local bằng Postman:

1. Khởi động lại backend với profile `sql-log`, chỉ dùng dữ liệu giả (cách bật ở mục trên).
2. Import `postman/task-sql-caller.postman_collection.json`; dùng lại environment
   `postman/local.postman_environment.json`, chọn **Car Rental - Local**.
3. Chạy folder **Part 1 - API context**, một iteration, theo thứ tự 01 → 03. Request 01 tạo chi nhánh
   giả duy nhất và tự lưu mã. Request 02 đọc lại kèm query marker. Request 03 cố tình thiếu đầu vào:
   phải là **400 / INVALID_REQUEST**, không phải 500. Mọi `pm.test` phải xanh.
4. Lấy `X-Request-Id` trong response 02 rồi tìm chính ID đó ở console IntelliJ. Sự kiện SELECT phải có
   `[api=GET /api/v1/admin/branches/CN-…]`; **không có** `?probe=not-in-api-context` trong nhãn.
   Postman Console cũng in chuỗi nhãn cần tìm, không dump dữ liệu request. Request 03 có thể không
   phát sinh log vì lỗi đầu vào không bị ghi ERROR — không yêu cầu tạo sự kiện chỉ để quan sát.
5. Đợi một nhịp job (mặc định 30 giây), tìm SQL `UPDATE availability.reservation`: phải có
   `[api=ReservationHoldExpiryScheduler.sweep]` và `requestId=job-…` mới mỗi lượt.
6. Tắt profile và khởi động lại: không còn SQL log. Các sự kiện HTTP/job khác nếu có vẫn mang nhãn;
   log khởi động chưa có context không in `[requestId=] [api=]` rỗng.

Postman chỉ kiểm response/header, không đọc được log máy chủ; test tự động phía trên kiểm nội dung
MDC và pattern. Nếu không thấy SQL, kiểm profile rồi khởi động lại đúng tiến trình cổng 8081; nếu
`{{baseUrl}}` đỏ thì chọn environment. Chuỗi `caller` được bổ sung từ Phần 2.

File của Phần 1 (đường dẫn từ gốc repo):

| Trạng thái | File |
|---|---|
| Tạo | `car-rental-api/src/main/java/com/carrental/shared/logging/ScheduledJobObservationHandler.java` |
| Tạo | `car-rental-api/src/main/java/com/carrental/shared/logging/LogValueSanitizer.java` |
| Sửa | `car-rental-api/src/main/java/com/carrental/shared/logging/RequestIdFilter.java` |
| Sửa | `car-rental-api/src/main/java/com/carrental/shared/logging/JobLoggingTaskDecorator.java` |
| Sửa | `car-rental-api/src/main/java/com/carrental/shared/logging/FailureSummary.java` |
| Sửa | `car-rental-api/src/main/java/com/carrental/shared/config/SchedulerLoggingConfiguration.java` |
| Sửa | `car-rental-api/src/main/resources/application.yml` |
| Tạo | `car-rental-api/src/test/java/com/carrental/shared/logging/ScheduledJobObservationHandlerTest.java` |
| Tạo | `car-rental-api/src/test/java/com/carrental/shared/logging/CorrelationPatternTest.java` |
| Sửa | `car-rental-api/src/test/java/com/carrental/shared/logging/RequestIdFilterTest.java` |
| Sửa | `car-rental-api/src/test/java/com/carrental/shared/logging/RequestIdApiIntegrationTest.java` |
| Sửa | `car-rental-api/src/test/java/com/carrental/shared/logging/FailureSummaryTest.java` |
| Sửa | `car-rental-api/src/test/java/com/carrental/shared/logging/JobLoggingTaskDecoratorTest.java` |
| Sửa | `car-rental-api/src/test/java/com/carrental/shared/error/ApiFailureLoggingTest.java` |
| Sửa | `car-rental-api/src/test/java/com/carrental/shared/config/SchedulerLoggingConfigurationTest.java` |
| Sửa | `car-rental-api/src/test/java/com/carrental/availability/adapter/in/scheduler/ReservationHoldExpirySchedulerTest.java` |
| Tạo | `postman/task-sql-caller.postman_collection.json` |
| Sửa | `README.md` |

Environment Postman dùng lại, không thêm biến hoặc secret. Chỉ tầng logging dùng chung thay đổi ở
production; `availability` chỉ mở rộng test wiring scheduler, không đổi service/SQL/transaction.

### Mở rộng SQL caller — Phần 2: nơi gọi từng câu SQL

Chỉ profile `sql-log` chọn `SqlCallerLogger`, appender kế thừa SLF4J của P6Spy và gắn
`SqlCallerFormatter` (`MessageFormattingStrategy`) ngay trong constructor. P6Spy gán lại strategy
sau khi tạo appender nên setter cũng giữ formatter này, không để formatter mặc định làm mất caller
hoặc xuống dòng. Không đăng ký hai lớp này thành Spring bean; không tự đặt system property toàn cục.
Không bật profile: datasource decorator vẫn tắt hẳn, cấu hình không tham chiếu hai lớp caller.

Mỗi sự kiện SQL có dạng `SQL (<ms> ms):`, xuống dòng `caller: ...`, rồi nguyên câu SQL đã thay tham số.
StackWalker chỉ chạy khi formatter nhận SQL không rỗng. Chuỗi caller:

- Theo thứ tự **trong → ngoài**: adapter ← service ← controller; không đảo thứ tự stack.
- Chỉ bỏ tiền tố `com.carrental.`, giữ module/package để hai `VehicleSearchQueryService` không trùng nhãn.
- Bỏ frame framework, proxy/CGLIB và tầng logging; tối đa **12 frame hợp lệ**, thêm `[truncated]` nếu còn.
- Không có frame ứng dụng thì `-`; thiếu debug metadata thì ghi rõ `Unknown Source` hoặc tên file,
  không bịa số dòng. Số dòng thực tế phụ thuộc phiên bản code, ví dụ ở trên chỉ minh họa.
- Không thêm query/body/header/URL JDBC. SQL có tham số vẫn chỉ được bật với dữ liệu local giả theo
  security-guideline §3. Không sửa response, không bật `%L`/`%M`, không viết lại SQL nghiệp vụ.

Kiểm tự động từ `car-rental-api/`, Docker Desktop đang hoạt động:

```bash
./mvnw '-Dtest=SqlCallerFormatterTest,SqlCallerLoggerTest,SqlCallerProfileTest,SqlLoggingIntegrationTest,ArchitectureRulesTest' test
./mvnw -Dspring.profiles.active=sql-log -Dtest=SqlLoggingIntegrationTest test
```

Cả hai lượt phải `BUILD SUCCESS`, không failure/error. Lượt đầu không đặt `SPRING_PROFILES_ACTIVE`
ở terminal: integration phải đi qua Hikari trực tiếp và không sinh log SQL. Các unit test formatter
có chủ đích phát hai sự kiện giả không có nghĩa datasource đã bật proxy. Lượt thứ hai phải log SQL
thật có caller, tham số và ms; rollback trong test vẫn thật. Không cần xóa volume hoặc gõ SQL tay.

| Test | Điều được chứng minh |
|---|---|
| SqlCallerFormatterTest.preservesModulePathsAndInsideOutOrder | Giữ module và thứ tự adapter-service-controller, phân biệt hai tên class giống nhau trên metadata mẫu |
| SqlCallerFormatterTest.filtersInfrastructureAndGeneratedProxyFrames | Không đưa framework/proxy/tầng log vào caller |
| SqlCallerFormatterTest.capsAtTwelveAcceptedFramesAndMarksTruncation | Lọc trước, giới hạn 12 frame sau, có dấu cắt khi vượt |
| SqlCallerFormatterTest.keepsExactlyTwelveFramesWithoutTruncationMarker | Đúng 12 không báo cắt giả và không che frame đệ quy |
| SqlCallerFormatterTest.usesDashWhenNoApplicationFrameExists | Không có nguồn gọi ứng dụng thì hiện `-` |
| SqlCallerFormatterTest.handlesMissingSourceMetadata | Thiếu số dòng/file hoặc native không làm formatter lỗi |
| SqlCallerFormatterTest.preservesExpandedMultilineSqlAndElapsedTime | Giữ ms, SQL đã thay tham số và comment nhiều dòng; bỏ URL JDBC/prepared riêng |
| SqlCallerFormatterTest.ignoresEmptySql | SQL rỗng không có caller |
| SqlCallerLoggerTest.retainsCallerFormatterWhenP6SpySetsDefaultStrategy | Cố gán SingleLineFormat không làm mất caller hoặc xuống dòng |
| SqlCallerProfileTest.defaultConfigurationNeverLoadsCallerOrDecoratesDataSource | Cấu hình YAML mặc định không proxy, vẫn khởi tạo khi classloader cấm hai lớp caller |
| SqlCallerProfileTest.sqlLogProfileSelectsCallerAppenderExplicitly | Chỉ YAML profile chọn đúng appender, không còn log-format cũ |
| SqlLoggingIntegrationTest.logsEveryExecutionWithExpandedValuesAndPreservedNewlinesOnlyWhenEnabled | SQL PostgreSQL thật có caller với source line khi bật profile, không có sự kiện khi tắt |
| SqlLoggingIntegrationTest.preservesApplicationTransactionAndRollback | Bọc formatter/appender không thay đổi rollback nghiệp vụ |

Test metadata mẫu **không thay thế** test luồng tìm xe thật phân biệt hai service. Luồng đó, HTTP/job
SQL và đồng thời request được kiểm ở Phần 3 bên dưới.

Postman: import lại `postman/task-sql-caller.postman_collection.json`, chọn **Car Rental - Local**.
Khởi động lại app với `--spring.profiles.active=sql-log` trong Program arguments của IntelliJ.
Chạy folder **Part 2 - SQL caller**, 01 → 03, một iteration; kết quả 201/200/400 và mọi `pm.test` xanh.
Lấy ID response 02 để tìm sự kiện SELECT trong console: phải có `api=GET /api/v1/admin/branches/CN-…`
không có query, và caller gồm `BranchReadAdapter.findByCode` ← `BranchQueryService.get` ←
`AdminBranchController.get`, giữ đường dẫn module cùng `File.java:dòng`. Không có tên lớp tầng log
hoặc `$$SpringCGLIB$$`. Tắt profile, khởi động lại và chạy lại folder: API vẫn đúng, không có SQL/caller.

File tạo ở Phần 2: `shared/logging/SqlCallerFormatter.java`, `shared/logging/SqlCallerLogger.java`
trong `car-rental-api/src/main/java/com/carrental/`; ba lớp test tương ứng `SqlCallerFormatterTest`,
`SqlCallerLoggerTest`, `SqlCallerProfileTest` trong `car-rental-api/src/test/java/com/carrental/shared/logging/`.
File sửa: `car-rental-api/src/main/resources/application-sql-log.yml`,
`car-rental-api/src/test/java/com/carrental/shared/sql/SqlLoggingIntegrationTest.java`,
`postman/task-sql-caller.postman_collection.json` và `README.md`. Không đổi environment hoặc secret.

### Mở rộng SQL caller — Phần 3: HTTP/job thật và đồng thời

`SqlCallerFlowIntegrationTest` dùng server HTTP cổng ngẫu nhiên, PostgreSQL Testcontainers và use case
thật của `branch`, `vehicle`, `search`, `availability`. Không mock service/SQL và không gọi controller
trực tiếp. Fixture xe đi qua DRAFT → PENDING_APPROVAL → ACTIVE; model UUID cô lập từng phép thử.
Clock của fixture tìm kiếm cố định ở 2030-01-15 để không phụ thuộc ngày chạy test.

Nhịp job **chỉ trong context test này** là 250 ms; nó vẫn là method `@Scheduled` production, đi qua
service/transaction/savepoint/SQL thật. Test tạo một HELD đã hết hạn trong transaction, đợi job nhả,
rồi đọc lại từ CSDL. Không gọi tay `sweep()` hoặc `expireHolds()`. Một filter chỉ có ở test đặt barrier
trước/sau hai request được chọn để chứng minh chúng thực sự cùng hoạt động; không thêm endpoint.

Chạy từ `car-rental-api/`, Docker Desktop đang hoạt động:

```bash
./mvnw '-Dtest=SqlCallerFlowIntegrationTest,ApiExceptionHandlerTest,ArchitectureRulesTest' test
./mvnw -Dspring.profiles.active=sql-log '-Dtest=SqlCallerFlowIntegrationTest,SqlLoggingIntegrationTest,ReservationWriteAdapterIntegrationTest,ReservationHoldIntegrationTest,ReservationDeadlockIntegrationTest,ReservationInsertRetryTest,ReservationHoldConcurrencyIntegrationTest' test
```

Cả hai lượt phải `BUILD SUCCESS`, không failure/error. Lượt đầu không có biến môi trường ép profile:
HTTP và job vẫn làm việc thật, datasource phải là Hikari trực tiếp và không có SQL/caller. Lượt thứ
hai phải có caller đúng chuỗi cùng source line dương. Các test availability kiểm lại lỗi `23P01`
đúng tên constraint, deadlock/savepoint/retry và cuộc đua giữ chỗ qua proxy; không thay kỳ vọng của
chúng. Không dùng lệnh xóa volume hoặc SQL gỡ constraint để kiểm phần này.

| Test trong SqlCallerFlowIntegrationTest | Điều được chứng minh |
|---|---|
| tracesRealBranchRequestWithoutQueryInApiContext | HTTP GET thật sinh SELECT có adapter ← service ← controller cùng số dòng; API không chứa query marker |
| distinguishesBothVehicleSearchServicesOnRealSearchRoute | API tìm được xe ACTIVE; cùng SQL ứng viên có cả `vehicle.application.service.VehicleSearchQueryService.list` và `search.application.service.VehicleSearchQueryService.search`, đúng thứ tự và số dòng |
| concurrentHttpRequestsKeepSeparateApiAndRequestIds | Hai request đọc chi nhánh/tìm xe vượt barrier trước và sau xử lý; log/SQL không lẫn API hoặc ID |
| rejectsInvalidSearchWithoutSqlOrInternalDetails | Thiếu tham số trả 400 INVALID_REQUEST trước SQL, giữ header và hợp đồng lỗi, không lộ caller/class |
| scheduledSweepUsesRealSqlAndCorrectJobIdentity | Job định kỳ thật chuyển HELD thành RELEASED; khi bật profile, các lượt UPDATE có job-UUID riêng, tên job và caller adapter-service-scheduler |

Nhánh không có profile không chỉ kiểm response: mỗi test thành công khẳng định bộ thu P6Spy không
có sự kiện nào, kể cả SQL nền của job. Nhánh bật profile kiểm log thực tế, không tự dựng chuỗi caller
để so với một chuỗi giả khác. Regex chỉ kiểm số dòng dương, không ghim số dòng dễ đổi khi sửa code.

File tạo: `car-rental-api/src/test/java/com/carrental/shared/logging/SqlCallerFlowIntegrationTest.java`.
File sửa: `README.md`. Không đổi production, migration, endpoint hoặc collection ở phần kiểm chứng
này; Postman dùng lại folder **Part 2 - SQL caller**. Kiểm đồng thời/job cần test tự động, không thể
chứng minh bằng chạy tuần tự Collection Runner.

### Sau review log 2 — Phần 1: dùng scheduler của Boot

Bỏ lớp scheduler riêng và bean `taskScheduler`; giữ customizer, decorator và observation handler.
Không đổi nghiệp vụ nhả giữ chỗ BR-103, SQL, transaction, nhịp chạy hoặc response API. Bỏ test pool
chỉ phục vụ bean tự định nghĩa; giữ các assertion về hành vi thật, không hạ kỳ vọng tên job.

Từ `car-rental-api/`, chạy lần lượt (Docker Desktop hoạt động, terminal không ép profile):

```bash
./mvnw clean '-Dtest=SchedulerLoggingConfigurationTest,JobLoggingTaskDecoratorTest,ScheduledJobObservationHandlerTest,ReservationHoldExpirySchedulerTest,FailureSummaryTest,ArchitectureRulesTest' test
./mvnw -Dspring.profiles.active=sql-log '-Dtest=SqlCallerFlowIntegrationTest#scheduledSweepUsesRealSqlAndCorrectJobIdentity,ReservationHoldExpirySchedulerTest' test
```

Lượt đầu dùng `clean` để dọn cả `.class` của scheduler/test đã xóa; không xóa dữ liệu CSDL local.
Cả hai lượt phải `BUILD SUCCESS`, không failure/error. Test cố tình ném lỗi job có ERROR/stack trace
là bình thường; đọc kết quả Maven, không kết luận theo một dòng ERROR riêng lẻ.

- `bootSchedulerLogsFailureOnceAndContinuesWithNewId`: scheduler pool chuẩn được Boot tạo nhận
  customizer; callback lỗi ghi một lần, giữ throwable và ID, lượt sau tiếp tục với ID mới.
- `realSchedulerContinuesAfterFailedInvocation`: job `@Scheduled` thật giữ tên
  `ReservationHoldExpiryScheduler.sweep` trong MDC và tóm tắt lỗi, rồi chạy lượt kế tiếp.
- `scheduledSweepUsesRealSqlAndCorrectJobIdentity`: timer thật nhả HELD qua PostgreSQL Testcontainers;
  log SQL có tên job, caller và ID riêng mỗi lượt. Không gọi tay `sweep()` hoặc sửa dữ liệu local.
- Các test decorator/observation/summary còn lại giữ kiểm phục hồi MDC, không nhận nhầm request
  có ID `job-`, và giữ nguyên stack trace. ArchitectureRulesTest kiểm ranh giới kiến trúc.

Đã xóa `shared/logging/ContextAwareTaskScheduler.java` trong main và test tương ứng; có thể khôi phục
từ commit trước review nếu cần. File sửa: `SchedulerLoggingConfiguration.java`,
`SchedulerLoggingConfigurationTest.java`, chú thích trong `JobLoggingTaskDecorator.java` và README.
Phần 1 không thay đổi giới hạn nhãn. Không thêm collection hoặc endpoint cho job nội bộ;
collection `postman/task-sql-caller.postman_collection.json` hiện có vẫn dùng được.

### Sau review log 2 — Phần 2: giới hạn nhãn api

Theo security-guideline §3, nhãn `api` do request tạo không được lặp lại một path dài tùy ý trên mỗi
sự kiện log. `LogValueSanitizer.escapeApi` dùng chung cho HTTP và observation job: tối đa **512**
đơn vị UTF-16 (`String.length()`), gồm `[truncated]`; emoji vẫn giữ nguyên cặp surrogate. Đo sau
escape vì một ký tự điều khiển có thể thành nhiều ký tự hiển thị. Dừng khi vượt giới hạn, không
dựng toàn bộ bản escape của path dài rồi mới cắt. Nhãn đúng 512 không bị cắt sớm hoặc gắn dấu giả.

Giới hạn này chỉ áp nhãn MDC `api`, không thay URI đầu vào, tham số SQL, requestId, routing, response,
stack trace hoặc các trường chẩn đoán khác. SQL kèm giá trị tham số vẫn chỉ dành cho local giả;
không bật với dữ liệu thật. Nhãn đã giới hạn được giữ qua error/async dispatch, không escape lại.

Chạy từ `car-rental-api/`, Docker Desktop hoạt động; terminal không ép profile trong lượt đầu:

```bash
./mvnw '-Dtest=LogValueSanitizerTest,RequestIdFilterTest,ScheduledJobObservationHandlerTest,RequestIdApiIntegrationTest,CorrelationPatternTest,FailureSummaryTest,ApiExceptionHandlerTest,SqlCallerFlowIntegrationTest,ArchitectureRulesTest' test
./mvnw -Dspring.profiles.active=sql-log -Dtest=SqlCallerFlowIntegrationTest test
```

Cả hai lượt phải `BUILD SUCCESS`, không failure/error. Các test thêm/mở rộng:

| Test | Điều được chứng minh |
|---|---|
| LogValueSanitizerTest.preservesValuesWithinLimit | Nhãn rỗng/ngắn/đúng 512 không thay đổi |
| LogValueSanitizerTest.truncatesOversizedValuesWithMarkerInsideLimit | 513, 1024 và 100000 ký tự đều bị giới hạn, dấu cắt tính vào 512 |
| LogValueSanitizerTest.appliesLimitAfterEscaping | LF thật nở thành escape; đúng 512 giữ nguyên, vượt thì cắt an toàn |
| LogValueSanitizerTest.neverSplitsEscapeAtTruncationBoundary | CR/LF/tab, quote, backslash, control và Unicode format không bị cắt dở escape |
| LogValueSanitizerTest.neverSplitsSupplementaryUnicode | Emoji ở biên không bị tách cặp surrogate |
| LogValueSanitizerTest.preservesExistingEscapingRules | Chuỗi ngắn và null giữ quy tắc escape cũ |
| LogValueSanitizerTest.doesNotTruncateGeneralDiagnosticEscaping | Escape thông điệp chẩn đoán không bị áp giới hạn API ngoài phạm vi |
| RequestIdFilterTest.limitsApiWithoutChangingRequestOrResponse | Các nhãn 511/512/513/4096 chỉ bị cắt trong MDC, chain nhận nguyên request và trả body/header như cũ |
| RequestIdFilterTest.boundsApiAfterControlCharacterExpansion | Path ngắn trước escape vẫn không vượt 512 sau escape |
| RequestIdFilterTest.preservesBoundedApiAcrossRedispatch | Async/error trên thread khác giữ nhãn đã cắt và ID ban đầu, dọn MDC sau xử lý |
| RequestIdFilterTest.isolatesConcurrentRequests | Hai request đồng thời không lẫn ID/nhãn cả với path ngắn lẫn dài |
| SqlCallerFlowIntegrationTest.boundsLongApiContextWithoutTruncatingDatabaseInput | HTTP thật trả 404 BRANCH_NOT_FOUND; khi bật profile, SQL vẫn nhận đủ mã dài nhưng MDC api chỉ 512, không có query marker |

Các test job thật, caller, lỗi 500 giấu nội bộ và correlation pattern được giữ; không tạo endpoint
test trong production. File mới: `LogValueSanitizerTest.java`. File sửa: `LogValueSanitizer.java`,
`RequestIdFilter.java`, `ScheduledJobObservationHandler.java`, `RequestIdFilterTest.java`,
`SqlCallerFlowIntegrationTest.java` trong package logging tương ứng, README và collection bên dưới.

Postman: import lại `postman/task-sql-caller.postman_collection.json`, giữ environment
`postman/local.postman_environment.json` (**Car Rental - Local**). Khởi động lại app với profile
`sql-log`, dữ liệu local giả; chạy **Part 3 - Bounded API context**, 01 → 02, một iteration.
Cả hai request chỉ đọc mã không tồn tại: phải 404 `BRANCH_NOT_FOUND`, mọi `pm.test` xanh.
Không phải lỗi khi response 404 ở hai ca phá hoại này, và không cần dọn dữ liệu.

Lấy `X-Request-Id` của từng response để tìm trong console IntelliJ. Request 01: nhãn `api` đủ 512,
không có `[truncated]`. Request 02: nhãn dài tối đa 512, kết thúc bằng `[truncated]`. Postman Console
in nhãn kỳ vọng để đối chiếu; test Postman không đọc được MDC của server. Query `probe` không được
nằm trong nhãn. Phần SQL bên dưới vẫn chứa đủ mã đầu vào dài — giới hạn không áp cho tham số SQL.
Nếu không thấy SQL, kiểm profile/khởi động lại app; nếu 500 hoặc sai mã lỗi, gửi response và log
theo requestId, không nới assertion.

### Mở rộng SQL caller — Phần 4: nghiệm thu tổng

Phần cuối chỉ hoàn thiện hướng dẫn; không đổi production, schema, endpoint hoặc hành vi nghiệp vụ.
Tóm tắt từng test và danh sách file nằm ngay cuối các Phần 1–3 phía trên. Hai lượt test Phần 3
(mặc định và `sql-log`) phải đạt trước lượt toàn bộ suite dưới đây.

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

- Collection của task này: `postman/task-sql-caller.postman_collection.json`, dùng environment
  `postman/local.postman_environment.json`, chọn **Car Rental - Local**. Import collection và chạy
  **Part 1 - API context**, rồi **Part 2 - SQL caller**, mỗi folder 01 → 03, một iteration.
  Mỗi folder tự tạo dữ liệu giả duy nhất, không cần chép mã hoặc dọn dữ liệu. Kỳ vọng lần lượt
  201/200/400 và mọi `pm.test` xanh; 400 phải là `INVALID_REQUEST`.
- Ở response 02 của Part 2, lấy `X-Request-Id`, tìm chính ID đó trong console IntelliJ: sự kiện SQL
  có nhãn `api=GET /api/v1/admin/branches/<mã>` không chứa query; caller giữ package/module và số dòng,
  theo thứ tự `BranchReadAdapter.findByCode` ← `BranchQueryService.get` ← `AdminBranchController.get`.
  Job có `api=ReservationHoldExpiryScheduler.sweep`; không cần dữ liệu giữ chỗ thật để thấy câu UPDATE.
  Postman không đọc được log máy chủ; test Phần 3 kiểm nội dung caller, tên job và cách ly request.
- Collection `postman/task-logging.postman_collection.json`, environment `Car Rental - Local`:
  **Part 1 - SQL logging** có hai request, **Part 2 - Request ID** có bốn request; mọi `pm.test` xanh.
- Bật profile với dữ liệu giả: SQL nhiều dòng có giá trị tham số, ms và ID trùng response header;
  SQL của job có `job-<UUID>` khác nhau qua mỗi lượt.
- Tắt profile rồi khởi động lại: API vẫn đúng, không còn sự kiện SQL từ P6Spy; requestId vẫn hoạt động.
- Lỗi 500, CR/LF thật và job lỗi đã được kiểm bằng test; không thêm endpoint lỗi hoặc phá CSDL để thử.
- `git status --short` không đưa `.env`, `.DS_Store`, log trong `target/` hoặc dữ liệu cá nhân vào commit.

Gửi phần `Results` (số test, failures/errors/skipped) và `Total time` sau `clean verify` để chốt
nghiệm thu; không suy số test hoặc thời gian từ lượt chạy cũ. Commit message đề xuất:
`Bổ sung ngữ cảnh API, job và chuỗi caller cho log SQL`. Tech Owner tự commit rồi nhờ Chief Architect
review; không thêm secret, log thực tế hoặc dữ liệu Testcontainers vào commit.

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

Lát cắt dọc đầu tiên: **tìm xe → báo giá → đặt xe → giữ chỗ → trả cọc**. Đã xong tìm xe và cơ chế giữ
chỗ. Thứ tự còn lại theo [ADR-0012](car-rental-docs/vi/decisions/adr-0012-vertical-slice-delivery.md)
(mục "Làm rõ 08/10/2026"): chốt BR-218 → `pricing` → `identity` → `booking` → `payment` →
`customer-web`.
