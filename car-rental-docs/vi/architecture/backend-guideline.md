# Backend Guideline

> Nền: ADR-0002 (Java + Spring Boot) · ADR-0003 (Native SQL) · ADR-0004 (Hexagonal).
> Cấu trúc code: [`module-architecture.md`](module-architecture.md).

## 1. Nền tảng
Java · Spring Boot · Modular Monolith · SOLID · Clean/Hexagonal · PostgreSQL + PostGIS + btree_gist ·
Native SQL qua `NamedParameterJdbcTemplate` · Flyway.

**Không dùng JPA repository cho truy vấn nghiệp vụ** trừ khi có ADR mới cho phép.

## 2. Ranh giới transaction

Application service sở hữu ranh giới transaction. Bắt buộc có transaction cho:

- Giữ chỗ và tạo đơn — **cùng một transaction**, không tách
- Xác nhận thanh toán (cọc, phần còn lại, thế chấp)
- Hoàn tiền và chuyển cọc khi đổi xe
- Phân công người lái và nhân viên giao xe
- Ghi biên bản bàn giao và chuyển đơn sang đang chạy
- Khoá lịch bảo dưỡng, đăng kiểm, điều chuyển
- Trừ hạn mức mã giảm giá
- Ghi nhận kỳ thanh toán hợp đồng dài hạn

### Quy tắc riêng cho đổi xe (BR-308)
Phải **giữ được chỗ trên xe mới trước khi nhả chỗ xe cũ**. Nếu giữ chỗ mới thất bại, toàn bộ
transaction quay lui và đơn cũ giữ nguyên. Không được để khách mất cả hai.

## 3. Chống tranh chấp

### Không đọc-rồi-ghi
Xem `module-architecture.md` §6. Ghi thẳng, để ràng buộc CSDL từ chối.

### Cập nhật nguyên tử cho bộ đếm
```sql
UPDATE promotion.promotion_code
SET used_count = used_count + 1
WHERE code = :code AND used_count < max_usage;
```
Kiểm số dòng bị ảnh hưởng: `1` là thành công, `0` là hết lượt.

### Ghi vào bảng có ràng buộc loại trừ — savepoint và thử lại deadlock

Áp cho `availability.reservation`, và cho bảng phân công tài xế, nhân viên giao xe sau này.

Hai câu ghi chồng lịch chạy cùng lúc có thể deadlock (`40P01`) — kể cả `UPDATE` chỉ đổi `status`.
Cơ chế và số đo ở `database-guideline.md` §4. Quy tắc:

1. **Mọi câu ghi** vào bảng đó chạy trong **savepoint** của transaction bên gọi
   (`TransactionTemplate` với `PROPAGATION_NESTED`, dựng trong persistence adapter).
2. Chỉ thử lại khi SQLSTATE là **`40P01`**, nhận diện qua `PSQLException`. Không bắt cả
   `PessimisticLockingFailureException` — nó gồm cả lock timeout `55P03`.
3. Tối đa **3 lần** tính cả lần đầu, giữ nguyên tham số. Hết lượt thì ném lại **đúng** lỗi deadlock đầu
   tiên — **không** đổi thành lỗi nghiệp vụ "xe đã bận", vì bên kia có thể rollback và xe vẫn trống.
4. Việc này nằm ở **adapter**. Application không biết SQLSTATE (R11).

Vì sao savepoint mà không thử lại cả transaction: transaction thuộc về bên gọi (`booking` giữ chỗ trong
transaction của nó). Savepoint cho phép thử lại đúng một câu ghi mà không bắt mọi bên gọi phải biết
chuyện này. Lần thử lại sẽ chờ bên thắng kết thúc: bên thắng commit thì nhận `23P01` — đúng là xe bận;
bên thắng rollback thì ghi thành công — đúng là xe trống.

### Idempotency cho mọi thao tác tiền
Kể cả thao tác do người thực hiện — nhân viên bấm hai lần là chuyện thường (ADR-0011).

## 4. Đọc tham số cấu hình

**Chỉ đọc `config` tại thời điểm tạo đơn**, rồi sao vào bản ghi đơn (ADR-0014).
Code quyết toán khi trả xe đọc từ bản sao trong đơn, **không** gọi lại `ConfigurationPort`.
Rule R9 khoá điều này.

## 5. Mã lỗi

Mã lỗi là **hợp đồng ổn định** — mobile bản cũ hiển thị thông báo dựa vào chúng. Không đổi tên mã
đã phát hành; thêm mã mới thì được.

| Mã | HTTP | Khi nào |
|---|---|---|
| `INVALID_REQUEST` | 400 | Request sai định dạng hoặc thiếu dữ liệu đầu vào bắt buộc |
| `BRANCH_NOT_FOUND` | 404 | Không tìm thấy chi nhánh theo mã nghiệp vụ |
| `VEHICLE_NOT_FOUND` | 404 | Không tìm thấy xe theo mã nghiệp vụ |
| `VEHICLE_PLATE_ALREADY_EXISTS` | 409 | Biển số đã thuộc về một xe khác — database-guideline §8 |
| `VEHICLE_INVALID_STATUS_TRANSITION` | 422 | Trạng thái xe không cho phép gửi duyệt hoặc duyệt — status-flow §4, BR-010 |
| `VEHICLE_INVALID_STATUS_TRANSITION` | 409 | Trạng thái xe đã thay đổi giữa lúc đọc và cập nhật có điều kiện; client cần đọc lại, không tự thử lại |
| `VEHICLE_DOCUMENT_MISSING` | 422 | Thiếu ngày hết hạn đăng kiểm hoặc TNDS tại thời điểm duyệt — BR-005 |
| `VEHICLE_DOCUMENT_EXPIRED` | 422 | Đăng kiểm hoặc TNDS đã hết hạn tại thời điểm duyệt — BR-005 |
| `VEHICLE_INVALID_SEATS` | 422 | Số chỗ ngoài `4/5/7/16` — khi tạo xe và khi lọc tìm kiếm (BR-018) |
| `INTERNAL_ERROR` | 500 | Lỗi hệ thống ngoài dự kiến; không tiết lộ chi tiết nội bộ |
| `VEHICLE_NOT_AVAILABLE` | 409 | Xe vừa bị đặt cho khoảng thời gian này |
| `DRIVER_NOT_AVAILABLE` | 409 | Tài xế đã có chuyến chồng giờ |
| `DELIVERY_STAFF_NOT_AVAILABLE` | 409 | Nhân viên giao xe đã bận |
| `QUOTE_EXPIRED` | 422 | Báo giá hết hạn |
| `HOLD_EXPIRED` | 422 | Quá 1 tiếng chưa trả cọc |
| `RESERVATION_NOT_FOUND` | 404 | Không tìm thấy khoá lịch theo mã `KL-<6>` |
| `RESERVATION_INVALID_STATUS_TRANSITION` | 422 | Trạng thái khoá lịch không cho phép thao tác — status-flow §2, BR-015 |
| `RESERVATION_INVALID_STATUS_TRANSITION` | 409 | Khoá lịch đã đổi giữa lúc đọc và lúc ghi có điều kiện (đổi trạng thái, dời mốc khoá giấy tờ); đọc lại, không tự thử lại |
| `BOOKING_INVALID_STATUS_TRANSITION` | 422 | Nhảy cóc trạng thái |
| `CANCELLATION_WINDOW_PASSED` | 422 | Ngoài cửa sổ huỷ |
| `DRIVER_LICENSE_NOT_VERIFIED` | 403 | Chưa xác minh GPLX (BR-106) |
| `DRIVER_LICENSE_EXPIRES_BEFORE_TRIP_END` | 422 | GPLX hết hạn trước khi chuyến kết thúc (BR-106b, BR-503b) |
| `VEHICLE_DOCUMENT_EXPIRED` | 409 | Đăng kiểm hoặc TNDS hết hạn trong khoảng thuê (BR-015, BR-016) |
| `DECLARED_DRIVER_REQUIRED` | 422 | Chuyến có tài xế mà chưa khai báo người lái (BR-502) |
| `PAYMENT_ALREADY_CONFIRMED` | 409 | Xác nhận lại khoản đã xác nhận |
| `PAYMENT_AMOUNT_MISMATCH` | 422 | Số tiền client gửi lệch với server tính |
| `COMMISSION_EXCEEDS_DEPOSIT` | 422 | Cấu hình hoa hồng vượt tỷ lệ cọc (BR-207) |
| `MILEAGE_CAP_EXCEEDED` | 422 | Đối tác đặt phụ phí vượt trần (BR-405) |
| `PROMOTION_CODE_EXHAUSTED` | 422 | Hết lượt dùng |
| `OUTSIDE_BRANCH_HOURS` | 422 | Giờ nhận/trả ngoài 06:00–23:00 (BR-119) |
| `BOOKING_WINDOW_VIOLATION` | 422 | Ngoài giới hạn đặt trước (BR-121) |
| `RENTAL_DURATION_TOO_SHORT` | 422 | Gói giờ ngắn hơn 4 giờ (BR-113) |
| `SEARCH_RENTAL_TYPE_NOT_SUPPORTED` | 422 | Gói tháng chưa hỗ trợ trong tìm kiếm ở lát cắt hiện tại (BR-125) |
| `SEARCH_DRIVE_MODE_NOT_SUPPORTED` | 422 | Có tài xế chưa hỗ trợ ở lát cắt hiện tại (BR-125) |
| `SEARCH_PICKUP_METHOD_NOT_SUPPORTED` | 422 | Giao tận nơi chưa hỗ trợ ở lát cắt hiện tại (BR-125) |
| `SEARCH_SORT_NOT_SUPPORTED` | 422 | Xếp theo đánh giá chưa hỗ trợ — làm khi có đánh giá (BR-126, BR-901) |
| `VEHICLE_DAILY_RATE_REQUIRED` | 422 | Duyệt xe chưa có giá ngày (BR-234, BR-010) |
| `VEHICLE_INVALID_DAILY_RATE` | 422 | Giá ngày không lớn hơn 0 hoặc không là bội số của 1.000đ (BR-234) |
| `QUOTE_NOT_FOUND` | 404 | Không tìm thấy báo giá theo mã `BG-<yymm>-<6>` |
| `QUOTE_RENTAL_TYPE_NOT_SUPPORTED` | 422 | Gói tháng chưa hỗ trợ báo giá ở lát cắt hiện tại (BR-125, ADR-0012) |
| `QUOTE_DRIVE_MODE_NOT_SUPPORTED` | 422 | Có tài xế chưa hỗ trợ báo giá ở lát cắt hiện tại (BR-125, ADR-0012) |
| `QUOTE_PICKUP_METHOD_NOT_SUPPORTED` | 422 | Giao tận nơi chưa hỗ trợ báo giá ở lát cắt hiện tại (BR-125, ADR-0012) |
| `HOLIDAY_CALENDAR_NOT_DECLARED` | 422 | Khoảng thuê kéo qua ngày cuối đã khai báo lịch lễ — từ chối báo giá và tìm kiếm (BR-218) |
| `VEHICLE_HAS_ACTIVE_BOOKINGS` | 409 | Thanh lý xe còn đơn hiệu lực (BR-014) |
| `CONTRACT_OVERDUE` | 403 | Hợp đồng có kỳ quá hạn, chặn nghiệp vụ khác (BR-224) |

**Bảng này được test giữ đồng bộ với enum `ErrorCode`** (từ Task 6b): thêm mã vào code mà không ghi
vào đây thì build đỏ. Chiều ngược lại được phép — bảng có sẵn những mã thiết kế trước cho module chưa
làm. Task 5 và Task 6 từng để lọt 8 mã vì việc này giao cho người nhớ.

## 6. Testing

- **Unit test cho domain** — chạy không cần Spring context, phải nhanh.
- **Integration test dùng Testcontainers với PostgreSQL thật.** Không dùng H2: H2 không có exclusion
  constraint, PostGIS hay `tstzrange`, nên test sẽ xanh trong khi production hỏng.

### Test đồng thời — bắt buộc, không phải tuỳ chọn

| Kịch bản | Kỳ vọng |
|---|---|
| 50 luồng cùng giữ chỗ một xe, một khung giờ | Đúng **1** thành công, 49 nhận 409 |
| Giữ chỗ chồng một phần khoảng đã xác nhận | Bị từ chối |
| Hai khoảng liền kề không chồng (10:00–12:00 và 12:00–14:00) | Cả hai thành công |
| Giữ chỗ đè lên khoảng bảo dưỡng | Bị từ chối |
| Giữ chỗ hết hạn → job dọn → giữ lại | Thành công |
| Hai luồng cùng xác nhận một khoản thanh toán | Chỉ ghi nhận một lần |
| Nhiều khách cùng dùng mã giảm giá còn 1 lượt | Đúng một người được |
| Hai luồng cùng phân công một tài xế chồng giờ | Đúng 1 thành công |
| Giữ chỗ chạy song song với khoá vận hành hoặc với xác nhận trên cùng khoảng, lặp ≥ 100 lần | 0 lỗi hạ tầng (không có `40P01` lọt ra) |

Kịch bản thứ ba đáng chú ý: `tstzrange` mặc định là `[)` nên hai khoảng liền kề **không** chồng nhau.
Đó là hành vi ta muốn, và test này khoá nó lại.

Test tuần tự không chứng minh được gì về những trường hợp trên.

**Test đồng thời phải chứng minh được là nó đỏ được.** Lỗi đồng thời thường chỉ hiện ở một phần nhỏ số
lần chạy, nên một lần xanh không nói gì. Với cơ chế chống lỗi (như thử lại deadlock), tắt cơ chế đó trên
một bản sao rồi chạy lại test: phải đỏ. Task 5: tắt thử lại thì cuộc đua giữ chỗ/khoá vận hành đỏ
36/200 lần; bật lại thì 0/100.
