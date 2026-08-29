# Database Guideline

> Nền: ADR-0003 (Native SQL) · ADR-0005 (ràng buộc loại trừ) · ADR-0007 (PostGIS) ·
> ADR-0008 (schema per module) · ADR-0013 (cấu hình theo thời gian) · ADR-0015 (bằng chứng bất biến).

## 1. Extension bắt buộc

```sql
CREATE EXTENSION IF NOT EXISTS btree_gist;   -- ràng buộc loại trừ (ADR-0005)
CREATE EXTENSION IF NOT EXISTS postgis;      -- tìm kiếm theo vị trí (ADR-0007)
CREATE EXTENSION IF NOT EXISTS pgcrypto;     -- hash số định danh
```

`btree_gist` là **bắt buộc**, không tuỳ chọn: ràng buộc loại trừ cần so sánh `=` trên `bigint` cùng
`&&` trên `tstzrange` trong một index GIST, và chỉ `btree_gist` cho phép điều đó.

## 2. Khoá chính và mã nghiệp vụ

- Khoá chính là `BIGINT GENERATED ALWAYS AS IDENTITY`. Không dùng UUID làm khoá chính.
- Mỗi aggregate có thêm **mã nghiệp vụ** duy nhất dùng trên URL. **Không đưa khoá chính số lên URL** —
  số tuần tự mời người ta dò, và đây là hệ thống có nhiều bên cùng nhìn vào một đơn.

| Aggregate | Mẫu mã | Ví dụ |
|---|---|---|
| Chi nhánh | `CN-<6>` | `CN-3TR7WK` |
| Xe | `XE-<6>` | `XE-8KQ4M2` |
| Đơn thuê | `DT-<yymm>-<6>` | `DT-2608-3XK9PQ` |
| Thanh toán | `TT-<yymm>-<8>` | `TT-2608-4M2XK9PQ` |
| Biên bản bàn giao | `BB-<yymm>-<6>` | `BB-2608-1AB2CD` |
| Hợp đồng dài hạn | `HD-<yymm>-<6>` | `HD-2608-7QW3ZR` |
| Chi trả đối tác | `CT-<yymm>-<6>` | `CT-2608-5NM8XV` |

Mã sinh ở tầng application, có ràng buộc `UNIQUE`.

## 3. Migration
Flyway. Mọi thay đổi qua migration, **không sửa file đã chạy**, đặt tên có tiền tố module:

```text
V001__extensions_and_schemas.sql
V002__identity_tables.sql
V003__branch_tables.sql
V004__vehicle_tables.sql
V005__availability_tables.sql
V006__availability_exclusion_constraints.sql
V007__config_tables.sql
V008__pricing_tables.sql
V009__booking_tables.sql
```

SQL luôn ghi rõ schema: `booking.booking`, không phải `booking`.
Tên bảng không lặp tên schema: `vehicle.vehicle`, không phải `vehicle.vehicle_vehicle`.

---

## 4. Bảng khoá lịch — phần quan trọng nhất tài liệu này

Mọi lý do khiến xe không cho thuê được đều nằm ở đây, **cùng một bảng**.

```sql
CREATE TABLE availability.reservation (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code            TEXT        NOT NULL,   -- định danh nghiệp vụ, dùng cho mọi chuyển trạng thái
    vehicle_id      BIGINT      NOT NULL,   -- ID logic sang schema vehicle, KHÔNG khoá ngoại
    period          TSTZRANGE   NOT NULL,   -- ĐÃ bao gồm khoảng đệm
    kind            TEXT        NOT NULL,
    status          TEXT        NOT NULL,
    booking_code    TEXT        NULL,       -- có giá trị khi kind = RENTAL; KHÔNG unique (gia hạn sinh nhiều bản ghi cho một đơn)
    reason          TEXT        NULL,       -- lý do khoá vận hành
    hold_expires_at TIMESTAMPTZ NULL,       -- bắt buộc khi status = HELD
    created_at      TIMESTAMPTZ NOT NULL,
    status_changed_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_reservation_code UNIQUE (code),

    CONSTRAINT chk_reservation_kind CHECK (kind IN (
        'RENTAL', 'MAINTENANCE', 'INSPECTION', 'TRANSFER', 'OWNER_BLOCK', 'COMPLIANCE_HOLD')),

    CONSTRAINT chk_reservation_status CHECK (status IN (
        'HELD', 'CONFIRMED', 'IN_USE', 'BLOCKED', 'RELEASED', 'COMPLETED')),

    -- Hình dạng khoảng: không rỗng, có cận dưới, cận dưới ĐÓNG và cận trên MỞ.
    -- `[)` phải ép ở đây, không chỉ trông vào SQL lúc ghi — nếu lọt `[]` thì
    -- mọi lượt thuê liền kề sẽ bị từ chối oan.
    CONSTRAINT chk_period_shape CHECK (
        NOT isempty(period)
        AND NOT lower_inf(period)
        AND lower_inc(period)
        AND NOT upper_inc(period)
    ),

    -- Chỉ COMPLIANCE_HOLD được phép không chặn trên (BR-015).
    -- Khoá vô hạn trên một đơn thuê là khoá xe vĩnh viễn.
    CONSTRAINT chk_unbounded_only_compliance CHECK (
        NOT upper_inf(period) OR kind = 'COMPLIANCE_HOLD'
    ),

    -- HELD phải có hạn, và hạn phải nằm sau lúc tạo.
    -- CSDL ép HÌNH DẠNG của bất biến; ĐỘ DÀI hạn là tham số cấu hình do ứng dụng giữ.
    CONSTRAINT chk_hold_has_ttl CHECK (
        status <> 'HELD'
        OR (hold_expires_at IS NOT NULL AND hold_expires_at > created_at)
    ),

    CONSTRAINT chk_rental_has_code CHECK (kind <> 'RENTAL' OR booking_code IS NOT NULL)
);

ALTER TABLE availability.reservation
  ADD CONSTRAINT reservation_no_overlap
  EXCLUDE USING gist (
      vehicle_id WITH =,
      period     WITH &&
  ) WHERE (status IN ('HELD', 'CONFIRMED', 'IN_USE', 'BLOCKED', 'COMPLETED'));

CREATE INDEX idx_reservation_vehicle_period
    ON availability.reservation USING gist (vehicle_id, period);
CREATE INDEX idx_reservation_hold_expiry
    ON availability.reservation (hold_expires_at) WHERE status = 'HELD';
```

### Ba điều không được phá

**Cột `kind` là mấu chốt.** Nếu tách `maintenance_schedule` thành bảng riêng, ràng buộc **không nhìn
thấy** lịch bảo dưỡng, và CSDL sẽ vui vẻ cho khách đặt thuê đè lên ngày xe nằm gara. Một bảng,
một ràng buộc, mọi lý do bận đều bình đẳng.

**`period` đã cộng khoảng đệm.** 2 giờ cho gói ngày, 1 giờ cho gói giờ (BR-109, BR-116). Cộng ở tầng
application khi tạo bản ghi, **không phải** khi hiển thị cho khách.

**`HELD` bắt buộc có hạn** — 1 tiếng (BR-103), có job dọn. Không có hạn thì xe bị khoá vĩnh viễn
bởi đơn khách bỏ dở.

`CHECK` chỉ ép **hình dạng**: có hạn, và hạn nằm sau lúc tạo. **Độ dài một tiếng là tham số cấu
hình** (BR-225) do ứng dụng giữ, không viết vào `CHECK` — nhúng một con số nghiệp vụ vào DDL nghĩa
là đổi nó phải chạy migration, đi ngược ADR-0013.

Nguyên tắc chung: **CSDL ép hình dạng bất biến, ứng dụng giữ giá trị chính sách.**

### `COMPLETED` vẫn nằm trong ràng buộc loại trừ — có chủ đích

Đơn thuê tới 12:00 với đệm 2 giờ có khoảng khoá `[…, 14:00)`. Khách trả xe lúc 12:00 và bản ghi
chuyển sang `COMPLETED` — nhưng **khoảng đệm tới 14:00 vẫn phải chặn**, vì xe chưa được dọn và nạp
nhiên liệu.

Nếu loại `COMPLETED` khỏi mệnh đề `WHERE`, một lượt thuê lúc 12:30 sẽ lọt. Giữ nó lại thì chính
`period` lo phần còn lại: bản ghi đã hoàn tất trong quá khứ không chồng với lượt tương lai, nên
không chặn nhầm ai.

`RELEASED` thì ngược lại — chỗ giữ đã nhả thì không được chặn gì nữa.

### Khoá do giấy tờ hết hạn — khoảng không chặn trên

`kind = 'COMPLIANCE_HOLD'` dùng cho trường hợp đăng kiểm hoặc TNDS hết hạn (BR-015).
Khoảng của nó **không có chặn trên**:

```sql
-- hết hạn ngày D, chặn mọi thứ từ D trở đi
INSERT INTO availability.reservation (vehicle_id, period, kind, status)
VALUES (:vehicle_id, tstzrange(:expires_at, NULL, '[)'), 'COMPLIANCE_HOLD', 'BLOCKED');
```

`tstzrange(x, NULL)` là khoảng vô hạn về phía trên, và ràng buộc loại trừ xử lý được nó bình thường —
mọi đơn thuê sau `D` đều bị từ chối.

Gia hạn giấy tờ thì **`UPDATE` điểm bắt đầu** tới hạn mới, không xoá rồi tạo lại. Xoá rồi tạo lại tạo
ra một khoảnh khắc xe không bị chặn, và dưới đồng thời thì khoảnh khắc đó đủ để lọt một đơn.

Mỗi xe có tối đa một `COMPLIANCE_HOLD` đang hiệu lực cho mỗi loại giấy tờ. Nếu cả đăng kiểm lẫn TNDS
cùng hết hạn thì lấy mốc sớm hơn.

### Cùng cơ chế cho người
```sql
ALTER TABLE dispatch.driver_assignment
  ADD CONSTRAINT driver_assignment_no_overlap
  EXCLUDE USING gist (driver_id WITH =, period WITH &&)
  WHERE (status IN ('ASSIGNED', 'IN_PROGRESS'));
```
Tương tự cho phân công nhân viên giao xe. Một người là tài nguyên vật lý không nhân bản được,
giống hệt chiếc xe.

---

## 5. PostGIS

```sql
ALTER TABLE branch.branch
  ADD COLUMN location geography(Point, 4326) NOT NULL;
CREATE INDEX idx_branch_location ON branch.branch USING gist (location);
```

```sql
SELECT b.id, ST_Distance(b.location, :origin) AS distance_m
FROM branch.branch b
WHERE ST_DWithin(b.location, :origin, :radius_m)
ORDER BY distance_m
```

`ST_DWithin` dùng được index không gian; công thức khoảng cách viết tay thì không, và sẽ quét toàn
bảng. Đừng tự viết lại.

### PostGIS ở schema `public`, và DDL phải ghi rõ schema

Extension cài vào schema **`public`** của `car_rental`. Nhưng ADR-0008 bắt mỗi module một schema
riêng — nên bảng ở schema `branch` dùng kiểu `geography` sẽ phải dựa vào `search_path` để tìm ra kiểu
đó ở `public`.

Chạy thì vẫn chạy, cho tới ngày ai đó siết `search_path` vì lý do bảo mật, và migration hỏng ở chỗ
rất khó đoán.

**Quy tắc:** mọi DDL và SQL nghiệp vụ **ghi rõ schema cho kiểu PostGIS**:

```sql
-- ĐÚNG
ALTER TABLE branch.branch ADD COLUMN location public.geography(Point, 4326) NOT NULL;

-- SAI — phụ thuộc search_path
ALTER TABLE branch.branch ADD COLUMN location geography(Point, 4326) NOT NULL;
```

Ngoài ra, `search_path` của database `car_rental` được **ghim tường minh** ở script khởi tạo, không
dựa vào mặc định của server.

**Vì sao không đặt PostGIS vào schema riêng (`extensions`).** Nghe sạch hơn, nhưng PostGIS ở schema
ngoài `public` là nguồn trục trặc đã biết: thứ tự khi `pg_dump`/`pg_restore`, và các công cụ mặc
định giả định `public`. Lợi ích thu được chỉ là một schema `public` gọn mắt — trong khi cái bảo vệ
thật sự là việc ghi rõ schema trong DDL, và việc đó không tốn gì.

---

## 6. Cấu hình có hiệu lực theo thời gian (ADR-0013)

```sql
CREATE TABLE config.business_parameter (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    param_key      TEXT        NOT NULL,
    value          JSONB       NOT NULL,
    effective_from TIMESTAMPTZ NOT NULL,
    changed_by     BIGINT      NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_param_lookup ON config.business_parameter (param_key, effective_from DESC);
```

**Không bao giờ `UPDATE` giá trị cũ** — luôn `INSERT` bản ghi mới với `effective_from` mới.
Mất lịch sử nghĩa là mất khả năng trả lời "lúc khách đặt đơn đó thì phí tài xế là bao nhiêu", và
đó chính là câu phải trả lời khi khiếu nại.

Danh sách ngày lễ là **bảng dữ liệu**, không phải hằng số trong code.

---

## 7. Bằng chứng bất biến (ADR-0015)

Bảng trong schema `handover` và `compliance`:
- **Không có thao tác `DELETE`.** Rule R8 quét SQL và chặn.
- Không `UPDATE` nội dung đã ký. Bổ sung thì thêm phiên bản mới.
- Ảnh và giấy tờ ở object storage; CSDL chỉ giữ khoá object.
- Ảnh bàn giao **giữ nguyên mốc thời gian và toạ độ** — đó là phần giá trị pháp lý.

Xoá tài khoản khách **không** xoá các bảng này (BR-123).

---

## 8. Ràng buộc bắt buộc khác

| Quy tắc | Cách ép |
|---|---|
| Một số điện thoại một tài khoản | `UNIQUE (phone_number)` trên `identity.account` |
| Một biển số một xe | `UNIQUE (plate_number)` trên `vehicle.vehicle` |
| `ownership_type` không đổi sau khi tạo | Trigger chặn `UPDATE` cột này |
| Xe PARTNER phải có chủ, xe COMPANY thì không | `CHECK ((ownership_type='PARTNER' AND host_id IS NOT NULL) OR (ownership_type='COMPANY' AND host_id IS NULL))` |
| Xe COMPANY phải thuộc chi nhánh | `CHECK (ownership_type <> 'COMPANY' OR branch_id IS NOT NULL)` |
| Một thao tác tiền chỉ chạy một lần | `UNIQUE (idempotency_key)` |
| Một đơn chỉ sinh một khoản chi trả | `UNIQUE (booking_code)` trên `settlement.payout` |
| Một khách đánh giá một đơn một lần | `UNIQUE (booking_code, reviewer_role)` |
| Số tiền không âm | `CHECK (amount >= 0)` |
| Hoa hồng không vượt tỷ lệ cọc | Kiểm ở application khi admin cấu hình (BR-207) |

## 9. Index thường dùng
`vehicle_id` · `branch_id` · `booking_code` · `customer_id` · `driver_id` · `status` ·
`ownership_type` · `rental_type` · `created_at` · `period` (GIST) · `location` (GIST) ·
`plate_number` · `phone_number` · `idempotency_key`.

## 10. Dữ liệu nhạy cảm
- Không lưu file nhị phân trong CSDL, chỉ lưu khoá object.
- Ảnh giấy tờ truy cập qua liên kết ký ngắn hạn, sinh mỗi lần, không cache.
- Số CCCD và GPLX: lưu bản hash để tìm kiếm, mã hoá bản rõ.
- Vị trí xe và tài xế là dữ liệu nhạy cảm — chỉ lộ khi đang có chuyến.
- Audit mọi lần đọc giấy tờ của người khác.
