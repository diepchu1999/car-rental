# API tìm xe — public

## Phạm vi

Task 6, lát cắt 1: `GET /api/v1/public/vehicles`, không cần đăng nhập.
Không tạo đường `/client`, `/mobile` hoặc endpoint giữ chỗ trong task này.

- BR-003/808: vị trí và thông tin nơi nhận xe lấy từ chi nhánh.
- BR-010/018/126: chỉ xe ACTIVE, lọc theo thuộc tính đã công bố.
- BR-104/109/116: loại mọi lịch bận chồng khoảng thuê **có đệm**.
- BR-015: khóa giấy tờ không chặn trên được tính bận qua availability.
- BR-110/209: `collateralFree` do module vehicle phân giải theo gói thuê.
- BR-112: không lọc, xếp hạng hoặc trả loại sở hữu.
- BR-113/119/121/125: kiểm điều kiện thuê ngay khi tìm kiếm, kể cả khi không có ứng viên.

Không có giá, ảnh, đánh giá, chi tiết xe, vòng đời đơn hay hỗ trợ xe đối tác trong lát cắt này.
Endpoint chỉ đọc dữ liệu công khai; không đọc thông tin khách, giấy tờ, chủ xe hoặc GPS.
Hiện chỉ chạy local. Rate limit theo security-guideline chưa được hiện thực trong task này.

## GET /api/v1/public/vehicles

Không có request body. Gửi các trường bằng query parameters, tên phân biệt hoa thường.

Header khuyến nghị:

```http
Accept: application/json
X-Client-Platform: web
X-Client-Version: 0.0.1
```

| Tham số | Kiểu | Bắt buộc / mặc định | Điều kiện |
|---|---|---|---|
| `latitude` | number | Bắt buộc | Hữu hạn, -90 đến 90 |
| `longitude` | number | Bắt buộc | Hữu hạn, -180 đến 180 |
| `startInclusive` | ISO-8601 timestamp | Bắt buộc | Giờ nhận xe, có `Z` hoặc offset |
| `endExclusive` | ISO-8601 timestamp | Bắt buộc | Giờ trả xe, sau giờ nhận; chưa cộng đệm |
| `rentalType` | enum | Bắt buộc | `HOURLY`, `DAILY`; `MONTHLY` chưa hỗ trợ |
| `driveMode` | enum | Bắt buộc | `SELF_DRIVE`; `WITH_DRIVER` chưa hỗ trợ |
| `pickupMethod` | enum | `BRANCH` | `DELIVERY` chưa hỗ trợ |
| `radiusKm` | number | Cấu hình, hiện 10 | Hữu hạn, > 0, không vượt cấu hình tối đa hiện 30 |
| `seats` | integer | Không lọc | 4, 5, 7 hoặc 16 |
| `transmission` | enum | Không lọc | `MANUAL`, `AUTOMATIC` |
| `fuelType` | enum | Không lọc | `PETROL`, `DIESEL`, `ELECTRIC`, `HYBRID` |
| `make` | string | Không lọc | Khớp toàn bộ, không phân biệt hoa thường, bỏ trắng hai đầu |
| `model` | string | Không lọc | Như `make`; không dùng tìm chuỗi con hoặc wildcard |
| `collateralFree` | boolean | Không lọc | Đúng chuỗi `true` hoặc `false` |
| `sort` | enum | `NEAREST` | `PRICE_ASC`, `RATING_DESC` chưa hỗ trợ |
| `limit` | integer | 20 | 1–100; vượt giới hạn trả lỗi, không tự cắt |
| `cursor` | string | Trang đầu | Giữ nguyên token nhận từ `data.nextCursor` |

Trường tùy chọn không dùng thì **bỏ khỏi URL**, không gửi giá trị rỗng, trắng hoặc `null` dạng chuỗi.
Mỗi tham số chỉ được xuất hiện một lần. Tham số không thuộc bảng bị từ chối bằng 400
`INVALID_REQUEST`, kể cả bộ lọc sở hữu hoặc giá chưa hỗ trợ; không âm thầm bỏ qua.
Enum viết đúng tên chữ hoa. Chuỗi hãng/dòng không có giới hạn độ dài nghiệp vụ tự đặt.
URL-encode giá trị: đặc biệt dấu `+` trong offset phải là `%2B`; dùng `Z` thì không gặp trường hợp này.

### Điều kiện thời gian

- Nhận/trả trong 06:00–23:00, gồm hai đầu, theo múi giờ đồng hồ ứng dụng
  (`car-rental.time-zone`, mặc định `Asia/Ho_Chi_Minh`). Không phụ thuộc múi giờ JVM hay offset khách gửi.
- Nhận tại chi nhánh: đặt trước ít nhất một giờ, tối đa sáu **tháng lịch** tính từ hiện tại.
- Gói giờ: ít nhất bốn giờ, đệm một giờ. Gói ngày: đệm hai giờ, không tự đặt thời lượng tối thiểu.
- Đệm chỉ cộng vào cuối khoảng khi kiểm lịch, đúng một lần trong availability.
  Giới hạn giờ chi nhánh chỉ áp lên hai thời điểm thực tế, không áp lên phần đệm.
- Các trạng thái HELD, CONFIRMED, IN_USE, BLOCKED, COMPLETED chặn trong khoảng đã lưu.
  HELD quá hạn chưa được dọn vẫn chặn; RELEASED không chặn.
- Khoảng là `[startInclusive, endExclusive)`: chạm đúng cận cuối không phải chồng lịch.

### Response 200

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "code": "XE-ABC123",
        "make": "Toyota",
        "model": "Vios",
        "seats": 5,
        "transmission": "AUTOMATIC",
        "fuelType": "PETROL",
        "collateralFree": true,
        "branch": {
          "code": "CN-ABC123",
          "name": "Central Branch",
          "address": "123 Main Street"
        },
        "distanceMeters": 111.2
      }
    ],
    "nextCursor": null
  },
  "error": null
}
```

Khoảng cách là **mét**, tính bằng PostGIS geography, không làm tròn trước khi sắp xếp/phân trang.
Response không có khóa chính nội bộ, biển số, loại sở hữu hoặc giá.
Không có xe phù hợp vẫn trả 200 với `items: []`, `nextCursor: null`, không trả 404.

### Cursor

- Thứ tự: khoảng cách tăng dần, bằng nhau thì **mã xe tăng dần theo thứ tự từ điển ASCII**.
  Không dùng ID nội bộ hoặc thứ tự tạo xe làm khóa phụ.
- Khi `nextCursor` khác null, truyền token đó vào `cursor` ở lần gọi sau và giữ nguyên điều kiện tìm.
- Có thể đổi `limit` giữa các trang; không đổi vị trí, khoảng thuê, gói, hình thức lái/nhận,
  bán kính, sort hoặc bộ lọc. Giữ nguyên cả cách viết hãng/dòng khi tiếp tục cursor.
- Từ Task 6b, token dùng **version 2**, chứa dấu SHA-256 của điều kiện và khóa tiếp nối
  `(khoảng cách, mã xe)`, không chứa ID số của xe; client phải coi là opaque.
  Đây không phải chữ ký xác thực hay quyền truy cập dữ liệu. Cursor version 1 bị từ chối bằng
  **400 `INVALID_REQUEST`** như cursor sai; khách bỏ cursor để tìm lại từ trang đầu.
- Không phải snapshot: lịch/trạng thái/vị trí thay đổi giữa hai lần gọi có thể làm thay đổi tập kết quả.
  Kết quả search **không giữ chỗ**. BR-104 vẫn do ràng buộc PostgreSQL quyết định khi hold.

### Lỗi

| HTTP | `error.code` | Trường hợp |
|---|---|---|
| 400 | `INVALID_REQUEST` | Thiếu bắt buộc; sai kiểu/enum; tọa độ, bán kính hoặc limit sai; khoảng không tăng; chuỗi trắng; cursor hỏng/sai phiên bản/sai điều kiện; tham số lạ/lặp |
| 422 | `VEHICLE_INVALID_SEATS` | Số nguyên chỗ ngồi không thuộc 4/5/7/16 |
| 422 | `RENTAL_DURATION_TOO_SHORT` | Gói giờ ngắn hơn bốn giờ |
| 422 | `OUTSIDE_BRANCH_HOURS` | Nhận/trả ngoài giờ chi nhánh |
| 422 | `BOOKING_WINDOW_VIOLATION` | Nhận quá sớm hoặc quá sáu tháng lịch |
| 422 | `SEARCH_RENTAL_TYPE_NOT_SUPPORTED` | Gói tháng |
| 422 | `SEARCH_DRIVE_MODE_NOT_SUPPORTED` | Có tài xế |
| 422 | `SEARCH_PICKUP_METHOD_NOT_SUPPORTED` | Giao tận nơi |
| 422 | `SEARCH_SORT_NOT_SUPPORTED` | Sắp theo giá hoặc đánh giá |
| 500 | `INTERNAL_ERROR` | Lỗi hệ thống; chi tiết chỉ có ở log máy chủ |

Ví dụ:

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "INVALID_REQUEST",
    "message": "limit must be between 1 and 100."
  }
}
```

Không chọn một lỗi bất kỳ làm bằng chứng: input có nhiều lỗi có thể bị từ chối ở điều kiện kiểm trước.
Ví dụ giờ nhận sau 30 phút nhưng ngoài giờ làm việc sẽ nhận lỗi giờ chi nhánh trước lỗi cửa sổ đặt.

## Kiểm chứng local

Import `postman/task-06-search.postman_collection.json` và `postman/local.postman_environment.json`.
Chọn environment **Car Rental - Local**, chạy thư mục **Part 6 - Public vehicle search** theo thứ tự.
Thư mục có thể chạy độc lập với Part 1/2, tự tạo chi nhánh/xe, sinh thời gian trong tương lai và truyền mã/cursor.
Mỗi lượt dùng model và biển số riêng, không cần xóa dữ liệu trước. Collection không tự xóa fixture.

- Xe chưa duyệt và xe ngoài bán kính không xuất hiện; xe gần xếp trước dù tạo sau.
- Bộ lọc, hai trang cursor và các request cố tình sai đều có `pm.test` kiểm mã HTTP/mã lỗi.
- Request nhận sau 30 phút tính mã lỗi mong đợi theo giờ thực tế; test HTTP với Clock cố định
  kiểm riêng `BOOKING_WINDOW_VIOLATION`, không phụ thuộc giờ người kiểm chạy collection.
- Khóa thuê/giấy tờ qua CSDL thật được kiểm trong `SearchApiIntegrationTest`;
  cuộc đua hai khách tìm rồi giữ trong `SearchHoldConcurrencyIntegrationTest`.
  Kiểm thủ công khóa lịch dùng IntelliJ SQL Console theo hướng dẫn nghiệm thu, không có REST giả cho availability.

### Kiểm cursor version 2 — Task 6b

Import `postman/task-06b-review.postman_collection.json`, dùng environment **Car Rental - Local**.
Khởi động lại backend sau khi cập nhật code, rồi chạy cả thư mục **Part 1 - Business-code cursor**
bằng Collection Runner, theo thứ tự 16 request. Không cần chạy collection Task 6 trước.

- Request 01–11 tự tạo hai chi nhánh và ba xe ACTIVE; model/biển số riêng mỗi lượt,
  thời gian thuê và hạn giấy tờ tự sinh. Fixture được giữ lại, không có thao tác xóa dữ liệu.
- Request 12–13 kiểm hai xe cùng vị trí theo thứ tự mã, sau đó xe xa hơn; không lặp/mất xe.
  Request 12 giải mã payload v2 và kiểm mã xe nằm ở cuối, không còn trường ID số.
- Request 14 dựng cursor v1 với đúng hash/khoảng cách của cursor vừa nhận và ID số mẫu;
  request 15 gửi cursor hỏng; request 16 dùng cursor đúng nhưng đổi bán kính.
  Cả ba phải nhận **400 `INVALID_REQUEST`**, mọi `pm.test` phải xanh.
- Kiểm độc lập trong Java còn đổi riêng ID nội bộ và giữ nguyên mã/khoảng cách:
  token phải không đổi. Không dò chuỗi chữ số trong hash để kết luận có hay không có ID.

### Kiểm thủ công khóa thuê và đệm gói ngày

Fixture của lượt nghiệm thu đã xác nhận: xe `XE-B76A44`, model
`T06-Search-7fa9992b-83b8-4993-8928-ded8c0f0d042`, ngày thuê 09/10/2026.
Đây là dữ liệu mẫu local, không phải mặc định hoặc quy tắc của API.

1. Chạy toàn bộ `car-rental-api/src/test/resources/sql/availability/manual_search_rental_fixture.sql`
   trong SQL Console IntelliJ, database `car_rental`. Phải COMMIT thành công để backend thấy dữ liệu.
   Script kiểm đúng xe/model/chi nhánh trước khi chèn, không ghi đè dữ liệu khi mã fixture bị trùng.
2. Import lại collection, chạy riêng **Part 6 - Manual rental and daily buffer**.
   Các request dùng bộ fixture đã xác nhận trực tiếp, không phụ thuộc biến `p6*` có thể mất khi import.
3. Ba request lần lượt kiểm tìm từ 10:00, 11:00, 12:00 tới 16:00 (giờ Việt Nam).
   Hai request đầu trả `XE-09Z3NS`, `XE-TTJ8LW`; request cuối trả
   `XE-09Z3NS`, `XE-B76A44`, `XE-TTJ8LW` theo khoảng cách rồi mã xe.
   Tất cả HTTP 200, mọi `pm.test` phải xanh.

Khóa `KL-T6M001` là RENTAL/CONFIRMED, lưu `[06:00,12:00)` cho chuyến trả 10:00 cộng hai giờ đệm.
Nhóm GET không thay đổi dữ liệu, chạy lại được khi fixture còn nguyên và thời gian vẫn hợp lệ.
Không chạy lại nhóm tạo 44 request hoặc cả collection trong bước này. Với lượt nghiệm thu khác,
cập nhật đồng bộ fixture SQL và nhóm request; không dùng ngày mẫu đã quá hạn BR-121.

### Kiểm thủ công khóa giấy tờ

Sau khi hoàn thành nhóm đệm gói ngày, chạy toàn bộ
`car-rental-api/src/test/resources/sql/availability/manual_search_compliance_fixture.sql`
trong SQL Console IntelliJ, database `car_rental`, và COMMIT thành công.
Script tạo `KL-T6M002` cho `XE-09Z3NS`, loại COMPLIANCE_HOLD, trạng thái BLOCKED,
từ 00:00 ngày 09/10/2026 giờ Việt Nam, không có cận trên (BR-015).
Đây chỉ mô phỏng khóa đã tồn tại; không kiểm job tạo khóa hay thay đổi hạn giấy tờ xe.

Import lại collection và chạy riêng **Part 6 - Manual compliance hold**:

1. Tìm 12:00–16:00 ngày 09/10/2026: khóa thuê của `XE-B76A44` đã hết đệm,
   nhưng `XE-09Z3NS` vẫn bị loại do khóa giấy tờ.
2. Tìm 12:00–16:00 ngày 10/10/2026: `XE-09Z3NS` tiếp tục bị loại.

Cả hai trả HTTP 200, danh sách theo thứ tự `XE-B76A44`, `XE-TTJ8LW`,
`nextCursor: null`; mỗi request có năm `pm.test` và tất cả phải xanh.
Giữ nguyên hai fixture SQL trong bước này; không chạy lại nhóm kiểm đệm gói ngày cũ
vì nhóm đó kỳ vọng trạng thái trước khi thêm khóa giấy tờ. Các GET có thể chạy lại
khi fixture còn nguyên và ngày mẫu còn hợp lệ theo BR-121.

### Kiểm thủ công đệm gói giờ

Giữ nguyên hai khóa mẫu trước đó. Chạy toàn bộ
`car-rental-api/src/test/resources/sql/availability/manual_search_hourly_fixture.sql`
trong SQL Console IntelliJ, database `car_rental`, rồi COMMIT.
Script tạo `KL-T6M003` cho `XE-TTJ8LW`, RENTAL/CONFIRMED, lưu `[06:00,11:00)`
ngày 09/10/2026 giờ Việt Nam: chuyến mẫu 06:00–10:00 cộng một giờ đệm (BR-113/116).
Đây là mô phỏng khoảng đã lưu; không thay thế test tự động kiểm policy tính đệm.

Import lại collection, chạy riêng **Part 6 - Manual hourly buffer** theo thứ tự:

| Request | Khoảng thuê gói giờ, giờ Việt Nam | Mã xe trả về theo thứ tự |
|---|---|---|
| 01 | 09/10/2026 10:00–16:00 | Danh sách rỗng |
| 02 | 09/10/2026 11:00–16:00 | `XE-TTJ8LW` |
| 03 | 09/10/2026 12:00–16:00 | `XE-B76A44`, `XE-TTJ8LW` |

Cả ba trả HTTP 200, `nextCursor: null`, mỗi request có năm `pm.test` phải xanh.
`XE-09Z3NS` vẫn bị khóa giấy tờ loại khỏi cả ba kết quả. Mốc 11:00 chứng minh
khóa đã lưu với đệm một giờ hết hiệu lực đúng biên, trong khi khóa đệm hai giờ
vẫn chặn; không tính lại đệm của khóa cũ theo gói của request tìm kiếm.
Các GET không thay đổi dữ liệu, chạy lại được khi fixture còn nguyên và ngày mẫu
còn hợp lệ theo BR-121. Không chạy cả collection hoặc nhóm đệm gói ngày cũ.

### Dọn khóa mẫu sau nghiệm thu

Sau khi cả ba nhóm kiểm thủ công đã đạt, chạy toàn bộ
`car-rental-api/src/test/resources/sql/availability/cleanup_manual_search_fixtures.sql`
trong SQL Console IntelliJ trên database local `car_rental`.
Script chỉ xóa `KL-T6M001`, `KL-T6M002`, `KL-T6M003` nếu mã xe, model, chi nhánh,
khoảng thời gian, loại, trạng thái và dấu nhận diện khác khớp dữ liệu mẫu đã tạo.
Nếu một bản ghi đã thay đổi, transaction bị hủy; chạy `ROLLBACK` và kiểm lại,
không bỏ điều kiện bảo vệ để cố xóa.

Sau COMMIT, `remaining_manual_reservations` phải bằng 0; truy vấn cuối vẫn trả
ba xe `XE-09Z3NS`, `XE-B76A44`, `XE-TTJ8LW` cùng chi nhánh tương ứng.
Chạy lại script không xóa thêm dữ liệu. Không xóa xe hoặc chi nhánh mẫu.
Có thể tạo lại ba khóa bằng các file fixture ban đầu (ID và thời điểm tạo sẽ mới),
nhưng phải chạy xen kẽ các nhóm Postman theo đúng thứ tự nghiệm thu.
Các nhóm Manual không còn kỳ vọng đúng ngay sau khi dọn khóa.

## Giới hạn đã biết

Search gọi tối đa ba truy vấn dữ liệu chính: chi nhánh → xe → xe bận; điều kiện thuê không truy cập CSDL.
Lát cắt 1 lấy toàn bộ ứng viên, loại bận rồi phân trang trong bộ nhớ, đã được Chief Architect duyệt.
Chỉ tối ưu khi có số đo trên dữ liệu thật. Không coi số lượng ứng viên lớn là vấn đề đã được đo.
