# Module Architecture — chuẩn bắt buộc cho code

> Áp dụng cho **mọi** module trong `com.carrental.<module>`.
> Danh sách module và ranh giới: [`module-map.md`](module-map.md).
> Quyết định nền: ADR-0003 (Native SQL) · ADR-0004 (Hexagonal + policy) · ADR-0008 (schema per module).

---

## 1. Nguyên tắc cốt lõi

- **Phụ thuộc chỉ hướng vào trong:** `adapter → application → domain`.
  `domain` không import gì từ `application` hay `adapter`. `application` không import `adapter`.
- **`domain` thuần Java** — không Spring, không JDBC, không HTTP, không Jackson.
- **Persistence là Native SQL**: `NamedParameterJdbcTemplate` + `RowMapper` + file `.sql`.
  Không `@Entity`, không JPA repository.
- **Mỗi module một schema**, tham chiếu chéo module bằng giá trị, không khoá ngoại.
- **Hai trục phân loại được phân giải thành policy một lần**, không rẽ nhánh rải rác (§7).

---

## 2. Cây thư mục chuẩn

```text
com/carrental/<module>/
├── api/                      # cổng PUBLIC cho module khác — chỉ đây mới được import từ ngoài
│   ├── <X>Directory.java         # interface tra cứu cross-module
│   └── <X>Ref.java               # record chia sẻ: id + code + field tối thiểu
├── domain/                   # lõi thuần, KHÔNG framework
│   ├── <X>.java                  # aggregate
│   ├── <X>Status.java            # enum trạng thái
│   └── policy/                   # interface policy (§7)
├── application/
│   ├── command/              # input GHI đã validate: <Verb><X>Command
│   ├── query/                # input ĐỌC: Search<X>Query, List<X>Query
│   ├── view/                 # read model trả ra: <X>Detail, <X>ListItem, <X>Summary
│   ├── port/in/              # use case: <Verb><X>UseCase
│   ├── port/out/             # SPI: Read<X>Port, Write<X>Port
│   └── service/              # impl use case: <X>CommandService, <X>QueryService
├── config/                   # (hiếm khi cần) lắp ráp bean riêng của module — @Configuration ở ĐÂY, không ở application
└── adapter/
    ├── in/rest/
    │   ├── client/           # /api/v1/client/**  → customer-web VÀ mobile dùng chung
    │   │   ├── request/
    │   │   └── response/
    │   └── admin/            # /api/v1/admin/**   → admin-web
    ├── in/internal/          # cài đặt api/<X>Directory — cổng module khác gọi vào
    ├── in/scheduler/         # job định kỳ: dọn HELD quá hạn, nhắc kỳ thanh toán
    └── out/
        ├── persistence/      # Native SQL
        │   ├── <X>ReadAdapter.java
        │   ├── <X>WriteAdapter.java
        │   ├── <X>SqlPaths.java
        │   └── <X>RowMappers.java
        ├── configuration/    # đọc tham số từ cấu hình ứng dụng — tạm, chờ module config (ADR-0013)
        ├── storage/          # S3/MinIO
        └── notification/     # push/SMS/email
```

SQL để ở `src/main/resources/sql/<module>/*.sql`, **không viết inline trong Java**.
Layer nào không dùng thì bỏ luôn thư mục. **Không dùng package `infrastructure`** — thay bằng `adapter/out`.

### Hai audience, không phải ba
Giai đoạn 1 chỉ có `client` và `admin`. **Mobile dùng chung `/api/v1/client/**`** — không tạo
namespace riêng cho mobile; hai namespace cho cùng một nghiệp vụ nghĩa là hai lần sửa cho mỗi thay
đổi rule, và chúng sẽ lệch nhau. `host` thêm ở GĐ2.

Tách audience **chỉ ở REST adapter**. `application` và `domain` dùng chung, không nhân bản.

---

## 3. Quy ước đặt tên

| Layer | Quy ước | Ví dụ |
|---|---|---|
| `domain` | `<X>`, `<X>Status` | `Booking`, `BookingStatus` |
| `application/command` | `<Verb><X>Command` | `CreateBookingCommand` |
| `application/query` | `Search<X>Query` (có phân trang), `List<X>Query` (không) | `SearchVehiclesQuery` |
| `application/view` | `<X>Detail`, `<X>ListItem`, `<X>Summary` | `BookingDetail` |
| `application/port/in` | `<Verb><X>UseCase` | `CreateBookingUseCase` |
| `application/port/out` | `Read<X>Port`, `Write<X>Port` | `ReadBookingPort` |
| `application/service` | `<X>CommandService`, `<X>QueryService` | `BookingCommandService` |
| `adapter/in/rest/client` | `Client<X>Controller` | `ClientBookingController` |
| `adapter/in/rest/admin` | `Admin<X>Controller` | `AdminFleetController` |
| `adapter/out/persistence` | `<X>ReadAdapter`, `<X>WriteAdapter` | `BookingWriteAdapter` |
| `api` | `<X>Directory`, `<X>Ref` | `VehicleDirectory`, `VehicleRef` |

**Quy ước chung**
- Service và adapter để **package-private**.
- `public`: `domain` (aggregate, enum, value object, policy interface) · `port` · `view` · `command` ·
  `query` · `api` · request/response.

  `domain` phải `public` vì `application` nằm ở gói khác và cần dùng aggregate lẫn enum. Cái được
  giấu là **cách làm** (service, adapter), không phải **cái được mô hình hoá**.
- Read model luôn ở `application/view`, không để ở `domain`.
- Một resource = **một Read port gộp** + **một Write port**. Port đặt theo resource, không theo module.
- Response map bằng `static fromDomain(view)` ngay trên record, không tạo lớp mapper riêng.
- Command tự validate trong `from(...)`, chỉ nhận giá trị thô — **không** import DTO của adapter.

### Verb chuẩn cho luồng đọc
| Verb | Trả về |
|---|---|
| `Get<X>` | `<X>Detail` — một bản ghi theo mã |
| `List<X>` | `List<...>` — không phân trang |
| `Search<X>` | `PageResponse<...>` — có phân trang và bộ lọc |
| `Get<X>Stats` | `<X>Stats` |

---

## 4. Luồng một request

```text
HTTP → Client<X>Controller (adapter/in/rest/client)
     → <Verb><X>UseCase (port/in)          ← interface
     → <X>Command/QueryService (service)   ← impl, điều phối
     → Read/Write<X>Port (port/out)        ← interface
     → <X>ReadAdapter/<X>WriteAdapter      → PostgreSQL (Native SQL)
return ← <X>Detail (view) → <X>Response → JSON
```

Controller chỉ phụ thuộc `port/in` và DTO. Service chỉ phụ thuộc `port/out` và view/command/query.
Không tầng nào nhảy cóc.

---

## 5. Cross-module

- Module B expose `api/<B>Directory` (interface) + `api/<B>Ref` (record: id + code + field tối thiểu).
- Module B cài đặt `<B>Directory` ở `adapter/in/internal/` — với B, module khác gọi vào cũng là một
  lối vào như REST hay scheduler.
- Module A import **chỉ** `com.carrental.<B>.api.*`.
- Toàn vẹn tham chiếu kiểm ở application qua `Directory`, không bằng khoá ngoại (ADR-0008).

---

## 6. Quy tắc cho tài nguyên tranh chấp

Đây là phần khác biệt lớn nhất của dự án này, vì tài nguyên ở đây **vật lý và không nhân bản được**.

**1. Không bao giờ đọc-rồi-ghi để quyết định "còn trống".**

```java
// SAI — luôn hỏng dưới đồng thời
if (availability.isFree(vehicleId, period)) {
    availability.reserve(vehicleId, period);
}

// ĐÚNG — ghi thẳng, để CSDL từ chối
try {
    availability.hold(vehicleId, period, bookingCode, ttl);
} catch (ReservationOverlapException e) {
    throw DomainException.conflict(ErrorCode.VEHICLE_NOT_AVAILABLE);
}
```

**2. Adapter được phép ném lỗi nghiệp vụ — ngoại lệ có chủ đích.**
`<X>WriteAdapter` bắt vi phạm ràng buộc loại trừ và dịch thành `ReservationOverlapException`.
Quy tắc chung là "kiểm tra nghiệp vụ ở service", nhưng chuyện "xe vừa bị người khác đặt" **chỉ CSDL
biết** — bắt service tự kiểm tra trước là quay lại đúng cái đọc-rồi-ghi mà ADR-0005 cấm.

**3. Giữ chỗ và tạo đơn nằm trong cùng một transaction.** Tách ra sẽ sinh chỗ giữ không chủ,
hoặc đơn không có chỗ.

**4. Mọi use case ghi tiền nhận `idempotencyKey`** và trả **cùng kết quả** khi gọi lại (ADR-0011).

**5. Cùng cơ chế cho tài xế và nhân viên giao xe** — họ là tài nguyên tranh chấp giống chiếc xe.

---

## 7. Phân giải policy cho hai trục

Hệ thống có hai trục cùng ảnh hưởng hành vi: `ownership_type` và `rental_type` (ADR-0004).

```java
// domain/policy/ — interface thuần, không Spring
public interface MileagePolicy   { int includedKm(int hours); }
public interface BufferPolicy    { Duration turnaround(); }
public interface FuelPolicy      { FuelSettlement settle(FuelReading out, FuelReading in); }
public interface LateReturnPolicy{ Money fee(Duration late, Money packagePrice); }
```

Phân giải **một lần ở biên use case**, rồi truyền policy đã phân giải vào domain:

```java
var policies = policyResolver.resolve(vehicle.ownershipType(), command.rentalType());
var booking  = Booking.create(command, quote, policies);
```

**Quy tắc:** chỉ lớp tên `*PolicyResolver` được phép `switch`/`if` trên `OwnershipType` và
`RentalType`. Đúng một chỗ cho mỗi họ policy. `domain` nhận policy đã phân giải và không tự hỏi
đơn thuộc loại nào.

Nếu một module thấy mình cần biết loại sở hữu mà không phải để phân giải policy — đó là tín hiệu
ranh giới sai, dừng lại và hỏi Chief Architect.

---

## 8. Mười hai rule khoá bằng test kiến trúc

`src/test/java/com/carrental/architecture/ArchitectureRulesTest` — quét source, **fail build** nếu vi phạm.

| # | Rule | Giữ quyết định nào |
|---|---|---|
| R1 | `application` không import `adapter` | ADR-0004 |
| R2 | `domain` không phụ thuộc `application`, `adapter`, hay framework nào (Spring/JDBC/web/Jackson) | ADR-0004 |
| R3 | Cross-module chỉ qua `api` | ADR-0008 |
| R4 | Write port không nhận read view (`*Detail`, `*ListItem`, `*Summary`) | — |
| R5 | `adapter/out/persistence` không import `jakarta.persistence` hay Spring Data JPA | ADR-0003 |
| R6 | Chỉ `*PolicyResolver` được rẽ nhánh trên `OwnershipType` và `RentalType` | ADR-0004 |
| R7 | Chỉ module `availability` chứa SQL ghi vào `availability.reservation` | ADR-0005 |
| R8 | Không có `DELETE`, `TRUNCATE`, hay `DROP TABLE` trong SQL của `handover` và `compliance` | ADR-0015 |
| R9 | `booking`, `handover`, `payment`, `settlement` không import `ConfigurationPort` — đọc bản sao trong đơn | ADR-0014 |
| R10 | Module `search` không import `VehicleRef`, và **không kiểu nào** `search` dùng từ `vehicle.api` có trường, thành phần record hay kiểu trả về là `OwnershipType` (mở rộng ở Task 6 — tên `VehicleRef` thôi thì chưa đủ) | BR-112 |
| R11 | `application` không import web/HTTP/servlet · JDBC/SQL · Jackson · `@Configuration`/`@Bean` | ADR-0004 |
| R12 | Record response trong `adapter/in/rest/**` không có thành phần kiểu số tên `id` hoặc kết thúc bằng `Id` — tham chiếu ra ngoài bằng **mã nghiệp vụ** (thêm ở Task 6b) | database-guideline §2 |

R11 cho phép `application` dùng Spring **chỉ để tiêm phụ thuộc và đánh dấu transaction** — chi tiết
và lý do ở ADR-0004, mục làm rõ 21/09/2026.

R6 → R11 là rule riêng của dự án này. Chúng tồn tại vì đó chính xác là sáu quyết định dễ mục nát
nhất — loại quy tắc con người vi phạm dần dần khi vội, nên phải để máy canh.

### Ghi chú về ba rule dễ hiểu sai

**R2 phủ cả chiều phụ thuộc, không chỉ framework.** §1 quy định `domain` không import gì từ
`application` hay `adapter`. Bản đầu của R2 chỉ cấm framework nên để lọt hai chiều đó — đã sửa.

**R9 nhắm vào bốn module chạm tiền trên đơn đã tồn tại**, không phải riêng `settlement`.
"Quyết toán" theo ADR-0014 là lúc **trả xe** — tính vượt km, phí trễ, nhiên liệu, hư hỏng — và việc
đó nằm ở `booking`, `handover`, `payment`. `settlement` (chia tiền với đối tác) chỉ là một phần, và
nó thuộc giai đoạn 2. Định nghĩa R9 chỉ theo `settlement` sẽ rỗng suốt giai đoạn 1 và bỏ sót đúng
chỗ có rủi ro.

Các module khác **được phép** đọc `ConfigurationPort` bình thường — `fleet` đọc chu kỳ bảo dưỡng,
`notification` đọc số ngày báo trước. Ràng buộc chỉ áp cho tiền trên một đơn đã tồn tại.

**R4 dựa vào quy ước đặt tên `Write<X>Port`** (§3). Port ghi cố tình đặt tên khác sẽ lọt. Chấp nhận:
R4 tồn tại để bắt lỗi vô ý, và lỗi vô ý thì tuân theo quy ước đặt tên. Không dựng thêm rule chỉ để
chống người cố tình né.

---

## 9. Checklist tạo module mới

1. Tạo `com/carrental/<module>/` theo cây §2, chỉ giữ layer cần dùng.
2. Migration Flyway `V<nnn>__<module>_<mô_tả>.sql`, tạo schema riêng.
3. Service và adapter để package-private.
4. SQL ở `resources/sql/<module>/`, adapter inject `SqlLoader` + `NamedParameterJdbcTemplate`.
5. Tái dùng tiện ích `shared/`, không tự viết lại.
6. `./mvnw -q test-compile` và `ArchitectureRulesTest` phải xanh.

---

## 10. Quy trình thêm một API

**Xác định READ hay WRITE trước.**

| | READ (`GET`) | WRITE (`POST/PATCH/DELETE`) |
|---|---|---|
| Input | `Query` | `Request` → `Command` (tự validate) |
| Port | `Read<X>Port` | `Write<X>Port` |
| Service | `@Transactional(readOnly = true)` | `@Transactional` |
| Sau khi ghi | — | **reload rồi trả view** |

Thứ tự làm: **từ trong ra ngoài** — query/command → view → port/in → port/out → adapter persistence
→ service → response → controller → verify.

**Quy tắc rút ra**
- GET không bao giờ có `Command`, `Request`, hay reload.
- Validate ở command, không ở controller.
- Kiểm tra nghiệp vụ ở service, không ở adapter — **trừ** trường hợp §6.2.
- Input ghi không phải read view.
- Tái dùng port trước khi tạo port mới.
- URL theo resource, dùng **mã nghiệp vụ** không dùng khoá chính số.
