# Module Map

> 16 vùng nghiệp vụ trong [`../business/domain-map.md`](../business/domain-map.md) thành module code,
> phụ thuộc được phép, và những gì cấm tuyệt đối.
>
> Chuẩn code **bên trong** mỗi module: [`module-architecture.md`](module-architecture.md).

Gói gốc: `com.carrental`

---

## 1. Danh sách module

### Nền tảng

| Module | Schema | Trách nhiệm | GĐ |
|---|---|---|---|
| `identity` | `identity` | Tài khoản (một SĐT một tài khoản), vai, giấy tờ CCCD/GPLX, hồ sơ vi phạm | 1 |
| `branch` | `branch` | Chi nhánh, địa điểm, giờ làm việc, quản lý, yêu cầu hỗ trợ chéo | 1 |
| `config` | `config` | Tham số kinh doanh có hiệu lực theo thời gian, lịch ngày lễ (ADR-0013) | 1 |
| `storage` | — | Port lưu trữ object: ảnh xe, giấy tờ, biên bản | 1 |
| `messaging` | `messaging` | Outbox, phát sự kiện nội bộ | 1 |
| `notification` | `notification` | Push · SMS · email · in-app, theo BR-1001 → BR-1008 | 1 |
| `audit` | `audit` | Nhật ký thao tác bất biến | 1 |

### Nguồn cung

| Module | Schema | Trách nhiệm | GĐ |
|---|---|---|---|
| `vehicle` | `vehicle` | Danh mục xe, `ownership_type`, `fuel_type`, thuộc chi nhánh, duyệt lên sàn | 1 |
| `fleet` | `fleet` | Bảo dưỡng, đăng kiểm, điều chuyển chi nhánh, bản ghi nạp nhiên liệu | 1 |
| `availability` | `availability` | **Nguồn sự thật duy nhất** về xe rảnh hay bận | 1 |

### Nhu cầu

| Module | Schema | Trách nhiệm | GĐ |
|---|---|---|---|
| `search` | — | Tìm xe theo vị trí và thời gian (read model, không sở hữu bảng gốc) | 1 |
| `pricing` | `pricing` | Bảng giá, loại ngày, chiết khấu, phí tài xế/giao xe/bảo hiểm, mã giảm giá, báo giá | 1 |
| `booking` | `booking` | Vòng đời đơn, gia hạn, đổi xe, huỷ | 1 |
| `dispatch` | `dispatch` | Người lái được khai báo, phân công nhân viên giao xe | 1 |
| `handover` | `handover` | Biên bản bàn giao, ODO, nhiên liệu, chữ ký, xác nhận hai phía | 1 |
| `contract` | `contract` | Hợp đồng dài hạn, kỳ thanh toán, đình chỉ, thu hồi xe | 1 |

### Tiền

| Module | Schema | Trách nhiệm | GĐ |
|---|---|---|---|
| `payment` | `payment` | Cọc, phần còn lại, thế chấp, hoàn tiền, hoá đơn VAT | 1 |
| `settlement` | `settlement` | **Nơi duy nhất dòng tiền phân nhánh theo loại sở hữu** | 2 |

### Sau chuyến

| Module | Schema | Trách nhiệm | GĐ |
|---|---|---|---|
| `incident` | `incident` | Sự cố, xác định lỗi ba bậc, bảo hiểm | 1 |
| `compliance` | `compliance` | Phạt nguội, hồ sơ bằng chứng, truy vết người lái theo thời điểm | 1 |
| `review` | `review` | Đánh giá 5 sao, bình luận, phản hồi | 1 |

---

## 2. Chiều phụ thuộc cho phép

```text
                        ┌──────────────┐
      search ──────────►│ availability │◄────────── fleet
        │               └──────▲───────┘              │
        │                      │                      │
        │                      │                      ▼
        ▼                      │                  vehicle ◄──── branch
     pricing ◄──── booking ────┘                      ▲
        ▲              │                              │
        │              ├──────────► dispatch ─────────┘
      config           ├──────────► handover
                       ├──────────► payment ─────────► settlement
                       ├──────────► contract
                       └──────────► compliance ◄───── incident
                                                          │
                                                       review

  identity · branch · config · storage · messaging · notification · audit
  → hạ tầng dùng chung, mọi module gọi được qua cổng `api/`.
```

## 3. Cấm tuyệt đối

| Vi phạm | Vì sao | Quy tắc |
|---|---|---|
| `search` biết `ownership_type` | Loại sở hữu không phải tiêu chí xếp hạng; biết là sẽ dần thành nơi cài thiên vị | BR-112, BR-126 |
| Module ngoài `availability` ghi vào bảng khoá lịch | Phá bảo đảm của ADR-0005 | ADR-0005 |
| Module nào tự tính "xe còn trống" | Như trên | BR-104 |
| `payment` biết về hoa hồng | Mọi thay đổi chính sách hoa hồng sẽ đụng vùng nguy hiểm nhất | ADR-0011 |
| `vehicle` phụ thuộc `booking` | Nguồn cung không được biết về nhu cầu | — |
| Code quyết toán đọc `config` thay vì đọc bản sao trong đơn | Âm thầm tính lại đơn cũ khi admin đổi cấu hình | ADR-0014 |
| `if` trên `ownership_type` hoặc `rental_type` ngoài `*PolicyResolver` | Rải rác thì không ai dám sửa | ADR-0004 |
| Xoá cứng dữ liệu trong `handover` và `compliance` | Bằng chứng pháp lý | ADR-0015 |

Bảy dòng đầu là ranh giới. Dòng cuối là nghĩa vụ pháp lý. Cả tám đều được khoá bằng test kiến trúc
(`module-architecture.md` §8).

---

## 4. Vì sao tách như vậy

**`availability` tách khỏi `vehicle`.** `vehicle` là danh mục, đổi chậm, đọc nhiều. Khoá lịch là dữ
liệu ghi nhiều, tranh chấp cao. Trộn chúng làm bảng xe nóng lên vô cớ. Quan trọng hơn: tách ra mới
nói được câu "chỉ `availability` được ghi lịch xe", và khoá câu đó bằng test.

**`handover` tách khỏi `booking`.** Biên bản bàn giao có vòng đời riêng, bất biến, và có giá trị
pháp lý (ADR-0015). `booking` thì thay đổi liên tục. Trộn chúng khiến quy tắc "không sửa, không xoá"
không áp được cho cả bảng.

**`compliance` tách khỏi `incident`.** `incident` xử lý sự cố trong chuyến và kết thúc. `compliance`
phục vụ truy vấn nhiều tháng sau và không bao giờ đóng. Hai nhịp thời gian rất khác nhau.

**`config` là module riêng, không phải bảng phụ.** Tham số có khoảng hiệu lực và có lịch sử
(ADR-0013). Để nó rải trong từng module thì mỗi module sẽ tự phát minh một kiểu lưu khác nhau.

**`search` không sở hữu schema.** Nó là read model đọc từ `vehicle` và `availability`. Cho nó sở hữu
bảng gốc là mở đường để nó dần biết những thứ không nên biết.

**`settlement` tách khỏi `payment`.** `payment` trả lời *tiền của khách đã vào chưa*. `settlement`
trả lời *tiền đó thuộc về ai*. Với xe công ty câu thứ hai là "toàn bộ về công ty" — nên GĐ1 không cần
module này. Nhưng tách sẵn từ đầu để khi thêm đối tác, phần thu tiền không phải sửa dòng nào.

---

## 5. Giao tiếp giữa module

Module chỉ được import gói `api` của module khác, không bao giờ import `domain`, `application` hay
`adapter` của nhau. Chi tiết: `module-architecture.md` §5.

Các cổng chính:

| Module cần | Module cung cấp | Qua |
|---|---|---|
| `booking` kiểm tra xe và lấy loại sở hữu | `vehicle` | `VehicleDirectory` → `VehicleRef` |
| `booking` giữ chỗ | `availability` | `AvailabilityDirectory.hold(...)` |
| `search` lọc xe đang bận | `availability` | `AvailabilityDirectory.findBusyVehicleIds(...)` |
| `search` tìm chi nhánh trong bán kính | `branch` | `BranchDirectory` — PostGIS, trả cả khoảng cách |
| `search` lấy xe hiển thị được | `vehicle` | `VehicleSearchDirectory` → `VehicleSearchView` |
| `search` và `booking` kiểm điều kiện thuê | `booking` | `RentalTermsDirectory` — đệm, tối thiểu, giờ chi nhánh, cửa sổ đặt |
| `fleet` khoá lịch bảo dưỡng | `availability` | `AvailabilityDirectory.block(...)` |
| `fleet` tạo khoá giấy tờ **lúc duyệt xe**, không đợi tới ngày hết hạn | `availability` | `AvailabilityDirectory.block(..., COMPLIANCE_HOLD)` |
| `fleet` dời khoá giấy tờ khi giấy tờ được gia hạn | `availability` | `AvailabilityDirectory.moveComplianceHoldStart(...)` |
| `compliance` truy vết người lái | `booking`, `dispatch` | `BookingDirectory`, `DriverAssignmentDirectory` |
| Mọi module đọc tham số | `config` | `ConfigurationPort` — **chỉ ở thời điểm tạo đơn** (ADR-0014) |

> `VehicleRef` chứa `ownershipType` là **ngoại lệ có chủ đích** — nhiều module cần nó để phân giải
> policy. Đổi lại, test kiến trúc R6 chặn việc dùng nó để rẽ nhánh trực tiếp.
>
> `search` **không** nhận `VehicleRef` đầy đủ; nó dùng một `VehicleSearchView` riêng không có
> `ownershipType`, qua một interface riêng `VehicleSearchDirectory` không có phương thức nào trả về
> `VehicleRef`. Đây là cách ép BR-112 bằng kiểu dữ liệu chứ không bằng kỷ luật. Cờ `collateralFree`
> (BR-209) được `vehicle` tính sẵn — vì nó phụ thuộc loại sở hữu mà `search` không được biết.
>
> `RentalType`, `DriveMode`, `PickupMethod` nằm trong `shared` như từ vựng dùng chung: `vehicle` cần
> `RentalType` để tính thế chấp, mà `vehicle` không được phụ thuộc `booking`. **Chỉ enum** — quy tắc
> theo gói thuê vẫn nằm ở `*PolicyResolver` của module sở hữu nó (ADR-0004).
> Điều kiện thuê thuộc `booking` vì đó là điều kiện của một lượt đặt; `search` áp lại đúng chúng để
> không hiện xe mà bước đặt sẽ từ chối (BR-125). `booking` có `api` và policy từ Task 6, vòng đời đơn
> từ Task 9.
