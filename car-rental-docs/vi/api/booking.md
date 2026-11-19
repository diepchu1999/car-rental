# API đơn thuê — client

## Phạm vi và quyền

- BR-102/103/104: tạo đơn và giữ chỗ cùng transaction; chỉ `availability.api` ghi lịch.
- BR-106/106b/128/130: CCCD/GPLX VERIFIED còn hiệu lực tới hết chuyến; hạng phù hợp xe.
- BR-122/ADR-0014: chép giá từ báo giá; đọc đơn không định giá lại.
- BR-131: mỗi khách tối đa một đơn `PENDING_PAYMENT`, ép bằng ràng buộc CSDL.
- BR-301/304: khách huỷ đơn chờ của mình, ghi lịch sử và nhả chỗ trong cùng transaction.
- BR-201/236: cọc 30% tổng gồm bảo hiểm cho gói giờ/ngày, làm tròn HALF_UP tới 1.000 VND;
  phần còn lại bằng tổng trừ cọc. Không phải khoản thế chấp và chưa có thao tác thu tiền.
- Cần access token có vai `CUSTOMER` và consent hợp lệ (BR-120/801).
  Chủ lấy từ danh tính đã xác thực, không nhận từ body/header tự khai/query string.
- Khách khác đọc mã đơn nhận `404 BOOKING_NOT_FOUND`, giống mã không tồn tại (security-guideline §2).

## Endpoint

Response dùng envelope chung và [X-Request-Id](http-headers.md), có `Cache-Control: no-store`.
Gửi `Authorization: Bearer <access-token>`, `X-Client-Platform`, `X-Client-Version` theo api-guideline.

| Method | Path | Thành công |
|---|---|---|
| POST | `/api/v1/client/bookings` | 201, Location theo mã đơn; phát lại cũng 201 |
| GET | `/api/v1/client/bookings/{code}` | 200, trạng thái hiện tại và snapshot đã lưu |
| GET | `/api/v1/client/bookings` | 200, trang đơn riêng của khách theo cursor |
| POST | `/api/v1/client/bookings/{code}/cancel` | 200, đơn CANCELLED với snapshot giữ nguyên |

Mã `DT-<yymm>-<6>` dùng tháng tạo theo giờ Việt Nam. Không đưa ID số lên URL hoặc response.
Hiện hỗ trợ `HOURLY`/`DAILY`, `SELF_DRIVE`, nhận/trả tại `BRANCH` theo lựa chọn trong báo giá.

## Tạo đơn

Content-Type `application/json`. Header **`Idempotency-Key` bắt buộc**, không được rỗng/trắng.
Client nên sinh UUID một lần cho một ý định đặt và giữ nguyên khi gửi lại do mất kết nối.

```json
{
  "quoteCode": "BG-2611-7QW3ZR"
}
```

`quoteCode` bắt buộc đúng dạng `BG-<yymm>-<6>`, tháng 01–12, sáu ký tự chữ hoa/số.
Trường ngoài hợp đồng bị bỏ qua: chủ, giá, cọc, mã đơn hoặc hạn tự khai không thay đổi kết quả hệ thống.

Key có phạm vi **tài khoản + chuỗi key chính xác**, giữ **24 giờ** (api-guideline §5):

- Cùng key còn hiệu lực và cùng `quoteCode`: phát lại **kết quả tạo ban đầu**, cùng mã đơn, giá, hạn,
  trạng thái ban đầu `PENDING_PAYMENT`, HTTP 201 và Location. Không giữ chỗ lần hai hoặc kiểm lại hạn báo giá.
- Cùng key còn hiệu lực, khác `quoteCode`: `409 IDEMPOTENCY_KEY_REUSED`.
- Đúng biên 24 giờ trở đi: không phát lại; request được kiểm như lần tạo mới,
  bao gồm hạn báo giá và giới hạn BR-131. Chưa có job dọn key.
- Request thất bại rollback claim cùng đơn/chỗ giữ, không lưu lỗi thành kết quả tạo thành công.
- Hai request cùng key chạy đồng thời chờ giao dịch đầu, sau đó trả cùng kết quả nếu giao dịch đầu commit.

Request mới kiểm chủ/hạn báo giá (`at >= expiresAt` là hết hạn), lựa chọn được hỗ trợ, xe ACTIVE,
điều kiện giấy tờ và thuê, rồi giữ chỗ và ghi đơn. Không kiểm xe trống bằng đọc-rồi-ghi.
Báo giá sai chủ trả `QUOTE_NOT_FOUND` trước kiểm hạn/giấy tờ. Hạn giữ chỗ lấy nguyên từ `availability.hold`,
không nhận TTL từ client. Header, dòng giá, lịch sử đầu tiên, key và hold cùng commit hoặc cùng rollback.

Response 201, `Location: /api/v1/client/bookings/DT-2611-9M2Q4A`:

```json
{
  "success": true,
  "data": {
    "code": "DT-2611-9M2Q4A",
    "quoteCode": "BG-2611-7QW3ZR",
    "vehicleCode": "XE-8KQ4M2",
    "branchCode": "CN-3ZP5K8",
    "startAt": "2026-11-13T03:00:00Z",
    "endAt": "2026-11-14T03:00:00Z",
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
      }
    ],
    "rentalAmount": 1000000,
    "insuranceFee": 100000,
    "totalAmount": 1100000,
    "depositRatio": 0.3,
    "deposit": 330000,
    "balance": 770000,
    "status": "PENDING_PAYMENT",
    "holdExpiresAt": "2026-11-10T04:00:00Z",
    "createdAt": "2026-11-10T03:00:00Z",
    "statusChangedAt": "2026-11-10T03:00:00Z"
  },
  "error": null
}
```

Ví dụ dùng bảo hiểm local thử 100.000 VND/ngày và giữ chỗ một giờ, không phải mức phí mặc định.
`lines` cùng cấu trúc [dòng báo giá](pricing.md), chỉ chứa tiền thuê; bảo hiểm là khoản riêng.
Tiền là nguyên VND, tỷ lệ dạng `0.3 = 30%`. `startAt`/`endAt` là giờ nhận/trả thực tế, không chứa đệm.
Không trả chủ nội bộ, mã khoá lịch, tham số cấu hình hoặc ID CSDL. Không điền km/phí trả trễ/nhiên liệu giả.

## Đọc đơn

GET trả cùng cấu trúc response, nhưng `status`/`statusChangedAt` là **trạng thái hiện đang lưu**.
Không tự huỷ theo đồng hồ, gia hạn hold, gọi lại pricing hoặc kiểm lại giấy tờ. Đơn hết hạn giữ chỗ vẫn đọc được.
Kiểm chủ trong SQL trước khi đọc dòng giá. Mã có nội dung được tra nguyên văn, không đổi hoa thường/cắt khoảng trắng.

## Khách huỷ đơn chờ

`POST /api/v1/client/bookings/{code}/cancel` không cần body hoặc `Idempotency-Key`.
Cần CUSTOMER và consent hợp lệ như khi đọc đơn; chủ lấy từ danh tính đã xác thực.
CUSTOMER kiêm vai nhân viên vẫn được huỷ đơn riêng; ADMIN thuần không dùng thao tác này để huỷ hộ.

- Chỉ cho phép `PENDING_PAYMENT → CANCELLED`. Huỷ lại trả `422 BOOKING_INVALID_STATUS_TRANSITION`.
- Đơn sai chủ và mã không tồn tại cùng trả `404 BOOKING_NOT_FOUND`.
- Không kiểm lại báo giá/giấy tờ/điều kiện thuê hoặc tính lại giá. Không thu/hoàn tiền vì đơn chưa trả cọc.
- Đơn còn PENDING_PAYMENT và hold chưa được nhả vẫn có thể được khách huỷ dù đã qua hạn chờ.
- Trạng thái, lịch sử (chủ đơn, mốc huỷ, lý do CUSTOMER_CANCELLED) và nhả chỗ cùng commit hoặc rollback.
- Hai yêu cầu huỷ đồng thời: một trả 200, một trả 422; không ghi thêm sự kiện huỷ hoặc nhả lần hai.

Response dùng cùng cấu trúc chi tiết đơn: chỉ `status` thành `CANCELLED` và `statusChangedAt` thành mốc huỷ.
Các mã tham chiếu công khai, dòng giá, tiền, giờ thuê và `holdExpiresAt` giữ nguyên.
GET sau đó trả trạng thái CANCELLED. Gửi lại request **tạo** với key còn trong 24 giờ vẫn nhận kết quả
tạo ban đầu (PENDING_PAYMENT), không tạo lại đơn hoặc giữ chỗ. Muốn đặt đơn mới phải dùng key mới và
báo giá còn hạn; mọi điều kiện đặt xe được kiểm lại.

Chưa hỗ trợ huỷ đơn đã trả cọc/xác nhận trong lát cắt này.

## Danh sách đơn của khách

`GET /api/v1/client/bookings?limit=20` dùng cùng quyền CUSTOMER và consent như đọc chi tiết.
Không yêu cầu giấy tờ vẫn còn hạn để đọc lịch sử. Không nhận mã tài khoản từ client; chủ lấy từ
CallerDirectory và được lọc ngay trong SQL (security-guideline §2).

| Query | Bắt buộc | Ý nghĩa |
|---|---|---|
| `limit` | Không | Số nguyên 1–100, mặc định 20 khi không gửi. Vượt 100 trả 400, không cắt ngầm |
| `cursor` | Không | Chuỗi opaque từ `nextCursor` của trang trước; bỏ hẳn tham số để tải trang đầu |

Tham số rỗng/trắng, lặp tên, sai kiểu hoặc tên ngoài hai trường trên đều trả `400 INVALID_REQUEST`.
Chưa có bộ lọc trạng thái, offset hoặc lựa chọn sắp xếp. Trả tất cả trạng thái đang được lưu,
bao gồm đơn CANCELLED và đơn có hạn giữ chỗ đã qua; không tự đổi trạng thái khi đọc.

Thứ tự **`createdAt` giảm dần, rồi mã đơn giảm dần** khi trùng thời điểm. Cursor không chứa khóa chính
số và chỉ dùng cho cùng khách; cursor hỏng, sai phiên bản hoặc của khách khác trả `400 INVALID_REQUEST`.
Client truyền nguyên cursor, không tự giải mã/tạo cursor. Có thể đổi `limit` giữa hai trang.

Response 200:

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "code": "DT-2611-9M2Q4A",
        "vehicleCode": "XE-8KQ4M2",
        "branchCode": "CN-3ZP5K8",
        "startAt": "2026-11-13T03:00:00Z",
        "endAt": "2026-11-14T03:00:00Z",
        "status": "PENDING_PAYMENT",
        "totalAmount": 1100000,
        "deposit": 330000,
        "balance": 770000,
        "holdExpiresAt": "2026-11-10T04:00:00Z",
        "createdAt": "2026-11-10T03:00:00Z"
      }
    ],
    "nextCursor": null,
    "hasMore": false
  },
  "error": null
}
```

Tiền là snapshot đã lưu theo BR-122, cùng giá trị với GET chi tiết; không tính lại từ giá xe hiện tại.
Danh sách không chứa dòng giá, ID số, chủ nội bộ, mã khóa lịch hoặc tham số cấu hình.
Khi còn hàng, `hasMore=true` và `nextCursor` là chuỗi; dùng nó cho lần GET kế tiếp.
Khi hết hàng, `hasMore=false`, `nextCursor=null`. Khách chưa có đơn nhận `items=[]`, không phải 404.

Phân trang không đóng băng cả tập kết quả: đơn mới nằm trước cursor xuất hiện khi tải lại trang đầu.
Mỗi trang phản ánh dữ liệu tại lần đọc; không cam kết trạng thái bất biến giữa hai request.

## Mã lỗi

| HTTP | `error.code` | Khi nào |
|---|---|---|
| 400 | INVALID_REQUEST | JSON sai, thiếu body/quoteCode/header key, key trắng, mã báo giá sai định dạng; query danh sách sai/lặp/lạ, limit ngoài 1–100, cursor sai hoặc của chủ khác |
| 401 | UNAUTHENTICATED | Thiếu token hoặc token không hợp lệ |
| 403 | ACCESS_DENIED | Không có quyền CUSTOMER hoặc sai quyền namespace |
| 403 | CONSENT_REQUIRED | Chưa có bằng chứng đồng ý điều khoản hợp lệ |
| 403 | IDENTITY_CARD_NOT_VERIFIED | Chưa có CCCD VERIFIED đang dùng |
| 403 | DRIVER_LICENSE_NOT_VERIFIED | Chưa có GPLX VERIFIED đang dùng hoặc bản cũ thiếu ngày cấp |
| 404 | BOOKING_NOT_FOUND | Mã đơn không tồn tại hoặc không thuộc người gọi |
| 404 | QUOTE_NOT_FOUND | Báo giá không tồn tại hoặc không thuộc người gọi |
| 404 | VEHICLE_NOT_FOUND | Xe không tồn tại hoặc không ACTIVE tại lúc đặt |
| 409 | IDEMPOTENCY_KEY_REUSED | Key còn hiệu lực nhưng khác quoteCode |
| 409 | BOOKING_PENDING_PAYMENT_EXISTS | Khách đã có đơn PENDING_PAYMENT |
| 409 | VEHICLE_NOT_AVAILABLE | Constraint từ chối khoảng giữ chỗ chồng lịch |
| 422 | QUOTE_EXPIRED | Báo giá đã đến/quá hạn khi thực hiện request mới |
| 422 | BOOKING_INVALID_STATUS_TRANSITION | Huỷ đơn không còn PENDING_PAYMENT, huỷ lặp hoặc thua cuộc đua huỷ |
| 422 | IDENTITY_CARD_EXPIRES_BEFORE_TRIP_END | CCCD hết hạn trước ngày trả xe theo giờ VN |
| 422 | DRIVER_LICENSE_EXPIRES_BEFORE_TRIP_END | GPLX hết hạn trước ngày trả xe theo giờ VN |
| 422 | DRIVER_LICENSE_CLASS_NOT_SUPPORTED | Hạng không được hỗ trợ trong bảng tương ứng ngày cấp |
| 422 | DRIVER_LICENSE_NOT_SUITABLE_FOR_VEHICLE | Hạng/số chỗ/hộp số không phù hợp |
| 422 | RENTAL_DURATION_TOO_SHORT | Không đủ thời lượng tối thiểu |
| 422 | OUTSIDE_BRANCH_HOURS | Giờ nhận/trả ngoài giờ chi nhánh |
| 422 | BOOKING_WINDOW_VIOLATION | Lúc đặt không còn đáp ứng cửa sổ nhận xe |
| 415 | INVALID_REQUEST | POST dùng Content-Type không hỗ trợ |
| 500 | INTERNAL_ERROR | Lỗi lưu trữ, hết lượt sinh mã hoặc lỗi nội bộ; không lộ SQL/class/stack trace |

Không chuyển deadlock hết lượt thử lại thành `VEHICLE_NOT_AVAILABLE`; chỉ overlap đúng constraint
mới được dịch sang mã xung đột lịch. Các trường hợp giấy tờ chi tiết xem [identity](identity.md).

## Kiểm bằng Postman

Import [collection Task 9](../../../postman/task-09-booking.postman_collection.json), dùng
[environment local](../../../postman/local.postman_environment.json), chọn **Car Rental - Local**.
Folder **Part 3.5 - Create and read booking**, chạy riêng A → B → C → D, một iteration:

- A: ADMIN thuần tạo hai xe ACTIVE với biển số ngẫu nhiên.
- B: CUSTOMER A có CCCD/GPLX VERIFIED phù hợp xe 5 chỗ số tự động, còn hạn qua ngày trả;
  chưa có đơn chờ trước lần đầu. Kiểm tạo, đọc, phát lại, key sai nội dung và giới hạn BR-131.
- C: CUSTOMER B khác A kiểm chống đọc đơn/dùng báo giá của A; chỉ cần consent, không cần giấy tờ.
- D: ADMIN thuần và request No Auth kiểm 403/401.

Chọn token ở Authorization của folder; request con kế thừa. Bật **Keep variable values** trong Runner,
không Share/Sync token. Script tự chuyển mã/key. Trường gửi ngoài hợp đồng không thay chủ/giá/cọc.

Collection lưu key, báo giá và snapshot theo mã tài khoản để chạy lại trong **24 giờ** mà không dọn CSDL.
Không xoá biến `t9bState-<mã tài khoản>` sau khi đã tạo đơn. Bộ request Phần 3.5 hiện giữ state theo key;
hết 24 giờ, dùng khách thử đủ điều kiện khác cho bộ request này, không xoá row để né BR-131.
Nếu dừng giữa báo giá và lần đặt đầu quá
15 phút, API trả `QUOTE_EXPIRED`; đây là hạn nghiệp vụ, không nới hạn chỉ để test.
Khoảng thuê sinh sau hai ngày lúc 10:00 giờ VN, trả sau 24 giờ; cần lịch lễ local đã khai báo tới ngày trả.
Các request phá hoại phải xanh với đúng HTTP/mã lỗi, không chỉ bất kỳ 4xx nào.

Folder **Part 3.6 - List own bookings** chỉ đọc và chạy lại được nhiều lần, không phụ thuộc key/báo giá
còn hiệu lực. Chạy riêng A → B → C bằng Runner, một iteration, Keep variable values bật:

- A: CUSTOMER đã có ít nhất một đơn, ví dụ khách A ở Phần 3.5. Tự đi hết từng trang (limit 1), so danh
  sách với chi tiết, kiểm mặc định 20/biên 100 và các tham số sai. Không cần tạo đơn mới.
- B: CUSTOMER khác A, có consent. Tự đi hết danh sách riêng, kiểm không chứa đơn A, không được tự khai
  chủ bằng query, đọc mã đơn A phải 404. B có đơn riêng vẫn chạy được.
- C: ADMIN thuần không kiêm CUSTOMER nhận 403; request No Auth nhận 401.

Chọn token tại từng folder, chạy từ request kiểm danh tính; không chạy cả ba folder bằng cùng token.
Request phân trang tự lặp trong Runner tới khi `hasMore=false`, nên tổng số lượt gửi có thể lớn hơn
số request hiển thị. Các biến `t9l*` tự được tạo khi chạy A; không sửa/xóa state `t9bState-*` của phần trước.
HTTP integration test kiểm riêng trường hợp chuyển trang với nhiều đơn cùng mốc và dùng cursor khác chủ;
không sửa CSDL local hoặc tạo thêm đơn chờ chỉ để có nhiều trang demo.

Folder **Part 4.3 - Cancel own pending booking**: chạy riêng A → B → C → D bằng Runner,
một iteration, Keep variable values bật. Job hết hạn chuyển đơn chờ quá hạn thành CANCELLED với lịch sử
HOLD_EXPIRED và nhả giữ chỗ cùng transaction từng dòng (BR-103/304). Một dòng lỗi không chặn các dòng
khác; lần dọn sau thử lại dòng đó. Đơn local còn PENDING_PAYMENT nhưng hold đã nhả cũng được job xử lý.
API đọc không tự chạy job; sau khi khởi động, đợi một nhịp dọn cấu hình trước khi kiểm trạng thái.

- A: CUSTOMER A đã có đơn PENDING_PAYMENT; tự đi hết danh sách để tìm đơn, rồi lưu snapshot.
- B: CUSTOMER khác A; huỷ mã đơn A và mã không tồn tại đều nhận 404.
- C: ADMIN thuần nhận 403, request No Auth nhận 401.
- D: dùng lại CUSTOMER A; huỷ không body/key nhận 200, huỷ lại nhận 422, GET giữ snapshot CANCELLED.

Chạy lại cả nhóm dùng đơn đã huỷ được ghi nhớ theo chủ, D kiểm 422 thay vì tạo thêm đơn.
Để kiểm lại nhánh 200, chuẩn bị một đơn chờ mới cho cùng khách rồi chạy A từ đầu.
Nếu lần đầu A không tìm được đơn chờ, chạy **Part 4.6 / A** để chuẩn bị đơn, rồi chạy lại Part 4.3 / A;
không tự huỷ đơn khác hoặc nới assertion. Request tìm đơn dùng biến local của Runner, phải chạy từ request 01.


Folder **Part 4.6 - Cancellation and expiry acceptance** chuẩn bị và kiểm bằng hạn thật (BR-103/301/304).
Sau khi import Replace, chạy **Part 3.5 / A - ADMIN booking vehicle fixtures** nếu chưa có biến xe thử.
Dùng CUSTOMER A có consent, CCCD/GPLX VERIFIED phù hợp xe 5 chỗ số tự động, còn hiệu lực hết chuyến:

1. **A - CUSTOMER A prepare cancellation booking**: tìm đơn chờ qua toàn bộ danh sách. Có đơn thì dùng
   lại và bỏ qua hai request báo giá/tạo đơn; chưa có thì tạo báo giá, key mới và đơn mới. Lưu hạn thật.
2. Chạy **Part 4.3 / A → B → C → D** theo đúng tài khoản mô tả ở trên để nghiệm thu huỷ chủ động.
3. **B - CUSTOMER A prepare expiry booking**: chuẩn bị một đơn chờ tiếp theo theo cùng cách; không huỷ
   đơn này. Request 05 trả `holdExpiresAt`; giữ backend chạy qua hạn đó cộng một nhịp job.
4. **C - CUSTOMER A verify actual expiry**: dùng đúng CUSTOMER A ở B, kiểm CANCELLED, các giá trị đóng
   băng không đổi, thử huỷ lại nhận 422 `BOOKING_INVALID_STATUS_TRANSITION`, rồi GET không thay đổi.

Chạy từng folder bằng Runner từ request 01, một iteration, bật Keep variable values. Biến `t9p*` được
script tự quản lý; không sửa state `t9bState-*` của Phần 3.5. Hai request bị skip khi đã có đơn chờ là
đúng thiết kế. Hạn giữ chỗ là 1 tiếng; nhịp job mặc định 30 giây, theo cấu hình local đang chạy.
Không sửa TTL hay dữ liệu đóng băng để giả hết hạn. `holdExpiresAt` có hậu tố Z là giờ UTC (giờ VN +7).
Lý do `HOLD_EXPIRED`, người thực hiện hệ thống và trạng thái giữ chỗ RELEASED phải đối chiếu bằng SQL
chỉ đọc; response HTTP không chứa lịch sử để khẳng định ba điều này. Sau C, mã đơn được lưu ở biến
collection `t9pExpiryCheckedBooking` phục vụ đối chiếu. Chạy lại C chỉ đọc và thử thao tác bị từ chối.
