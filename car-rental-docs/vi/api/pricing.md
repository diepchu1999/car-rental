# API báo giá — client

## Phạm vi

- BR-122: báo giá bất biến, có hạn dùng; đổi giá xe/cấu hình không đổi báo giá đã tạo.
- BR-114/218/235–237: giá gói giờ/ngày, loại ngày, giờ lẻ và làm tròn riêng từng dòng.
- BR-231: bảo hiểm là khoản riêng, không nằm trong `rentalAmount`.
- BR-113/119/121/125: dùng cùng điều kiện thuê của `rental.api.RentalTermsDirectory`.
- Không thu tiền, giữ chỗ hoặc kiểm lịch xe. Báo giá không cam kết xe còn trống (ADR-0005).
- Namespace `/client` cần access token hợp lệ có vai `CUSTOMER` (BR-120/801).
  Gửi `Authorization: Bearer <access-token>`; thiếu/token sai trả `401 UNAUTHENTICATED`,
  sai quyền namespace trả `403 ACCESS_DENIED`, giữ response envelope chung.
- BR-122: chủ báo giá lấy từ danh tính CUSTOMER đã xác thực, không nhận từ body.
  Khách khác dùng mã báo giá trả `404 QUOTE_NOT_FOUND`, giống mã không tồn tại.
  Báo giá lịch sử trước V015 chưa có chủ cũng trả lỗi này; khách xin báo giá mới, không gán chủ đoán.
- Không có phí tài xế/giao xe, giảm giá, cọc, phần còn lại hoặc thế chấp trong lát cắt này.

## Endpoint

Mọi endpoint áp dụng [X-Request-Id](http-headers.md) và response envelope thống nhất.

| Method | Path | Thành công |
|---|---|---|
| POST | `/api/v1/client/quotes` | 201, Location theo mã báo giá |
| GET | `/api/v1/client/quotes/{code}` | 200, đúng dữ liệu đã lưu |

Mã `BG-<yymm>-<6>`: `yymm` là tháng **tạo báo giá theo giờ Việt Nam**, không phải tháng nhận xe.
Không đưa khóa chính số lên URL hoặc response. Tham chiếu xe lưu bằng `vehicle_code` vì chỉ cần
đối chiếu/hiển thị; không lưu thêm ID, không có FK hoặc JOIN chéo schema (ADR-0008).
Chủ lưu bằng `customer_account_code` (`TK-<6>`) trong schema pricing, không FK sang identity.
V015 giữ NULL cho lịch sử; CHECK `NOT VALID` vẫn ép báo giá mới phải có chủ. Không sửa V009.

## Tạo báo giá

Content-Type `application/json`; gửi header `X-Client-Platform` và `X-Client-Version` theo api-guideline.

```json
{
  "vehicleCode": "XE-8KQ4M2",
  "startAt": "2026-11-13T03:00:00Z",
  "endAt": "2026-11-15T03:00:00Z",
  "rentalType": "DAILY",
  "driveMode": "SELF_DRIVE",
  "pickupMethod": "BRANCH"
}
```

Cả sáu trường bắt buộc, không nhận null; mã xe không được trắng. Thời điểm là ISO-8601 có offset;
hai mốc là giờ nhận/trả thực tế, không cộng đệm. `endAt` phải sau `startAt`.
Hai mốc phải biểu diễn được tới micro giây (tối đa sáu chữ số thập phân có nghĩa);
phần nhỏ hơn micro giây trả 400 `INVALID_REQUEST`. Không tự làm tròn vì có thể đổi số giờ tính tiền BR-237.
Các trường ngoài hợp đồng bị bỏ qua: client không quyết định mã báo giá, số tiền, hạn dùng hoặc chủ.
Ví dụ gửi `customerAccountCode` của người khác không đổi chủ; báo giá vẫn thuộc tài khoản gửi token.

Lát cắt 1 hỗ trợ `HOURLY`, `DAILY`, `SELF_DRIVE`, `BRANCH`.
Các giá trị enum đã biết nhưng chưa hỗ trợ bị từ chối bằng mã riêng; enum không đọc được trả 400.

Thứ tự kiểm lỗi tất định:

Biên xác thực/namespace và use case xác nhận CUSTOMER trước khi thực hiện nghiệp vụ báo giá.

1. JSON và cấu trúc command: đủ trường, mã có nội dung, khoảng dương.
2. Lựa chọn chưa hỗ trợ: MONTHLY → WITH_DRIVER → DELIVERY.
3. Điều kiện thuê: tối thiểu gói giờ → giờ chi nhánh → cửa sổ đặt.
4. Lịch lễ: **ngày lịch giờ trả xe theo giờ Việt Nam** vượt `declared-through` thì từ chối.
   Kiểm mốc `endAt` trực tiếp, không trừ một nano giây; ngày cuối đã khai báo được bao gồm.
5. Xe tồn tại và ACTIVE; xe nháp/ngừng hoạt động cũng trả `VEHICLE_NOT_FOUND`.
6. Tính giá, sinh mã, lưu và đọc lại trong cùng transaction.

Các điều kiện thuê áp đúng BR-113/119/121 như tìm kiếm: gói giờ đủ bốn giờ thực tế;
nhận/trả trong 06:00–23:00, gồm hai đầu; nhận tại chi nhánh báo trước ít nhất một giờ;
cửa sổ đặt tối đa sáu tháng **lịch**, chỉ kiểm thời điểm nhận. Không có giới hạn độ dài gói ngày
được tự thêm. Khoảng 01/03/2027–02/01/2028 vì vậy đi tới lỗi lịch lễ, nếu ngày nhận còn trong cửa sổ đặt.

Response 201, ví dụ `Location: /api/v1/client/quotes/BG-2610-7QW3ZR`:

```json
{
  "success": true,
  "data": {
    "code": "BG-2610-7QW3ZR",
    "vehicleCode": "XE-8KQ4M2",
    "startAt": "2026-11-13T03:00:00Z",
    "endAt": "2026-11-15T03:00:00Z",
    "rentalType": "DAILY",
    "driveMode": "SELF_DRIVE",
    "pickupMethod": "BRANCH",
    "lines": [
      {
        "type": "DAY_BLOCK",
        "dayType": "WEEKDAY",
        "startAt": "2026-11-13T03:00:00Z",
        "hours": 24,
        "dailyRate": 1000000,
        "uplift": 0,
        "amount": 1000000
      },
      {
        "type": "DAY_BLOCK",
        "dayType": "WEEKEND",
        "startAt": "2026-11-14T03:00:00Z",
        "hours": 24,
        "dailyRate": 1000000,
        "uplift": 0.2,
        "amount": 1200000
      }
    ],
    "rentalAmount": 2200000,
    "insuranceFee": 200000,
    "totalAmount": 2400000,
    "expiresAt": "2026-10-12T03:15:00Z",
    "createdAt": "2026-10-12T03:00:00Z"
  },
  "error": null
}
```

Ví dụ dùng bảo hiểm thử `PER_BILLING_DAY=100000`, không phải mức phí thật hoặc mặc định ứng dụng.
`lines` là các dòng tiền thuê; `insuranceFee` là một khoản riêng để giao diện hiển thị thành dòng bảo hiểm.
`rentalAmount` bằng tổng `lines[].amount`; `totalAmount = rentalAmount + insuranceFee`.
Mọi số tiền là nguyên VND. Giá và tỷ lệ tính bằng số thập phân chính xác, không dùng double/float.

| `type` | Ý nghĩa `hours` |
|---|---|
| DAY_BLOCK | 24 giờ tính tiền, kể cả mức thuê tối thiểu một ngày |
| REMAINDER_HOURS | Tổng giờ lẻ sau khối ngày, 1–4 |
| HOURLY_PACKAGE | 4 giờ đầu |
| OVERAGE_HOURS | Tổng giờ vượt gói đầu, 1–4 |

Mỗi dòng mang `dailyRate`, `dayType`, `uplift` và số tiền đã dùng. Lễ trùng cuối tuần lấy mức cao hơn;
nhãn theo mức thực sự được áp, bằng nhau chọn HOLIDAY. Không cộng dồn hai mức tăng.
Gói giờ trên tám giờ có đúng các dòng gói ngày; bảo hiểm cố định của gói giờ vẫn một ngày.

`expiresAt` tính một lần từ mốc tạo và hạn trong snapshot, mặc định 15 phút từ cấu hình.
Mốc tạo dùng độ chính xác micro giây của PostgreSQL; hạn dương có phần nano vẫn được giữ chính xác
bằng phần phụ nội bộ, không làm tròn hạn về 0 và không đưa trường kỹ thuật đó ra HTTP.
Sinh mã thử tối đa năm lần nếu trùng mã; không đọc trước để kiểm mã trống, không ghi đè báo giá cũ.
Lỗi ghi dòng hoặc reload rollback cả header và các dòng.

POST không dùng `Idempotency-Key`: tạo lại cùng đầu vào là **báo giá mới**, không phải thu tiền hoặc giữ chỗ.

## Đọc báo giá

GET giữ nguyên hợp đồng trên, đọc header/dòng từ schema `pricing`, không tra giá xe hoặc cấu hình mới.
Báo giá của đúng chủ dù quá hạn **vẫn trả 200**. Không trả thông tin snapshot hoặc hạn cho khách khác.
Kiểm `QUOTE_EXPIRED` khi dùng báo giá để đặt xe, không thuộc GET này.
Mã có nội dung được giữ nguyên khi tra cứu; không cắt khoảng trắng/đổi hoa thường.

Cổng nội bộ `pricing.api.QuoteDirectory.getForBooking(code, customerAccountCode, at)` nhận mã khách
đã xác thực và mốc tạo đơn từ use case booking. Kiểm chủ trước hạn: sai chủ/thiếu chủ/thiếu mã →
`QUOTE_NOT_FOUND`; đúng chủ nhưng `at >= expiresAt` → `422 QUOTE_EXPIRED`. Trả snapshot đã lưu,
không đọc lại cấu hình, không tính lại giá và không tự giữ chỗ. Cổng này không phải endpoint REST mới.

Để đặt xe bằng báo giá, dùng [`POST /api/v1/client/bookings`](booking.md) với body `quoteCode`
và header `Idempotency-Key`. Booking mới kiểm lại chủ/hạn, giấy tờ, điều kiện thuê và giữ chỗ;
không dùng GET báo giá như một cam kết xe còn trống. Cọc và phần còn lại xuất hiện trên response đơn.

Snapshot lưu giá ngày, tập cuối tuần, hai mức tăng, cách tính/giá trị/số ngày bảo hiểm và hạn dùng;
từng dòng lưu loại ngày/mức tăng đã áp. Không lưu toàn bộ lịch lễ vào mỗi báo giá; lịch sử lễ thuộc ADR-0013.
Không có endpoint cập nhật báo giá. V009 cũng chặn UPDATE header/dòng đã lưu tại CSDL.

## Mã lỗi

| HTTP | `error.code` | Khi nào |
|---|---|---|
| 400 | INVALID_REQUEST | JSON/enum/thời điểm sai, thiếu/null trường, mã trắng hoặc khoảng không dương |
| 404 | VEHICLE_NOT_FOUND | Xe không tồn tại hoặc không ACTIVE |
| 404 | QUOTE_NOT_FOUND | Mã không tồn tại, không thuộc khách đang gọi, hoặc lịch sử chưa có chủ |
| 422 | QUOTE_RENTAL_TYPE_NOT_SUPPORTED | MONTHLY chưa hỗ trợ |
| 422 | QUOTE_DRIVE_MODE_NOT_SUPPORTED | WITH_DRIVER chưa hỗ trợ |
| 422 | QUOTE_PICKUP_METHOD_NOT_SUPPORTED | DELIVERY chưa hỗ trợ |
| 422 | RENTAL_DURATION_TOO_SHORT | Gói giờ chưa đủ bốn giờ thực tế |
| 422 | OUTSIDE_BRANCH_HOURS | Giờ nhận/trả ngoài giờ chi nhánh |
| 422 | BOOKING_WINDOW_VIOLATION | Giờ nhận ngoài cửa sổ đặt |
| 422 | HOLIDAY_CALENDAR_NOT_DECLARED | Ngày giờ trả vượt lịch đã khai báo |
| 415 | INVALID_REQUEST | POST dùng Content-Type không hỗ trợ |
| 500 | INTERNAL_ERROR | Lỗi lưu trữ/mapping, hết lượt sinh mã hoặc lỗi reload |

Thông điệp tiếng Anh; không trả class, stack trace, SQL hoặc chi tiết kết nối ra response.

## Kiểm bằng Postman và SQL

Kiểm quyền sở hữu bằng [collection Task 9](../../../postman/task-09-booking.postman_collection.json),
folder **Part 2 - Quote ownership (BR-122)**, sau khi backend áp V015. Chọn environment **Car Rental - Local**.
Chạy riêng từng folder con A → B → C → D bằng Runner, một iteration: A dùng ADMIN; B/D cùng CUSTOMER A;
C dùng CUSTOMER B khác A. Chọn token ở Authorization của folder, request con kế thừa; không lưu token
vào biến hoặc Share/Sync token. Mã chi nhánh/xe/báo giá và mã khách truyền tự động qua collection.
Khoảng thuê sinh theo ngày chạy, nhận lúc 10:00 giờ VN sau hai ngày, trả sau 24 giờ; dữ liệu fixture
duy nhất mỗi lượt. Nếu khoảng này vượt lịch lễ đã khai báo, cập nhật cấu hình lịch lễ local trước khi thử.
Các request phá hoại phải có test xanh với **404 QUOTE_NOT_FOUND**, không phải chỉ nhận bất kỳ 4xx nào.
Biên hết hạn và dữ liệu cũ thiếu chủ được kiểm trong `QuoteQueryServiceTest`, `QuoteServiceIntegrationTest`
và `QuoteOwnerMigrationIntegrationTest` trên PostgreSQL container.

Import [collection Task 7](../../../postman/task-07-pricing.postman_collection.json) cùng
[environment local](../../../postman/local.postman_environment.json), chọn **Car Rental - Local**.
Sau khi backend áp dụng V009, chạy riêng thư mục **Part 3 - Immutable quotes (BR-122)** theo thứ tự 01–34.
Không cần chạy Phần 1 trước hoặc chép mã giữa request. Mỗi lượt tạo một chi nhánh/xe riêng với biển số ngẫu nhiên.

Bộ kỳ vọng dùng dữ liệu thử: `PER_BILLING_DAY=100000`, mức tăng cuối tuần `0.2`, lễ `0.5`, hạn `PT15M`.
Giữ các giá trị này khi chạy bộ ví dụ; đổi cấu hình hợp lệ có thể làm test số tiền cố định trượt.
Request 06–15 kiểm a–j; 17–18 kiểm k/k2 với giá 855000; 19 đọc lại báo giá cũ sau đổi giá xe.
Các request 4xx có chủ đích phải có `pm.test` xanh khi đúng mã lỗi.

Ngày nhận sớm nhất trong bộ là **10/11/2026 08:00 giờ Việt Nam**: chạy cả thư mục **trước 07:00 ngày đó**
để giữ đủ một giờ báo trước (BR-121). Sau mốc đó phải cập nhật bộ ngày/kỳ vọng; test tự động dùng Clock cố định
nên vẫn tái lập được. Giấy tờ fixture tới 01/01/2030, không phải chính sách nghiệp vụ.

Kiểm đổi cấu hình, đọc báo giá hết hạn và rollback bằng `QuoteServiceIntegrationTest`.
Kiểm trực tiếp mọi CHECK/UPDATE bất biến bằng IntelliJ SQL Console với file
`car-rental-api/src/test/resources/sql/pricing/verify_quote_constraints_manual.sql`, chạy toàn bộ trong session riêng.
Kết quả đúng: **22 thông báo PASS**, SQLSTATE **23514** và đúng tên constraint; sau ROLLBACK
`remaining_fixture_count = 0`. Nếu lỗi ngoài dự kiến, chạy ROLLBACK trước khi thử lại.
