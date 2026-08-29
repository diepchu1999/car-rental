# Architecture Overview

> Toàn cảnh kỹ thuật, và **các rủi ro kỹ thuật cao nhất suy ra từ 149 quy tắc nghiệp vụ**.
> Nghiệp vụ: [`../business/business-rules.md`](../business/business-rules.md).

---

## 1. Hệ thống này là gì, nhìn từ góc kỹ thuật

Một nền tảng phân bổ **tài nguyên vật lý khan hiếm theo thời gian**, có tiền đi kèm, và có nghĩa vụ
pháp lý về bằng chứng.

Ba đặc tính đó quyết định gần như mọi lựa chọn kỹ thuật trong tài liệu này:

| Đặc tính | Hệ quả |
|---|---|
| Tài nguyên vật lý không nhân bản được | Trùng lịch là lỗi phá sản niềm tin, phải chặn ở tầng CSDL |
| Có tiền | Mọi thao tác ghi tiền phải idempotent và ghi sổ chỉ-thêm |
| Có nghĩa vụ pháp lý | Hồ sơ bằng chứng bất biến, truy vấn được nhiều tháng sau |

## 2. Stack đã chốt

| Lớp | Công nghệ |
|---|---|
| Backend | Java · Spring Boot · Modular Monolith · Clean/Hexagonal |
| CSDL | PostgreSQL + PostGIS + btree_gist · Native SQL (`NamedParameterJdbcTemplate`), **không JPA** · Flyway |
| Xác thực | Keycloak (OIDC), có Authenticator tự viết cho OTP qua SMS |
| Web | React + TypeScript — `customer-web` và `admin-web` |
| Mobile | React Native (iOS + Android) |
| Lưu trữ | S3-compatible (MinIO ở local) cho ảnh và giấy tờ |
| Local | docker-compose |

Cách xây: **lát cắt dọc từng luồng nghiệp vụ**, không xây hết backend rồi mới tới frontend.

## 3. Bảy rủi ro kỹ thuật, xếp theo mức thiệt hại

### 3.1. Trùng lịch tài nguyên — rủi ro số một

BR-104 nói không hai khoá lịch nào được chồng thời gian trên cùng một xe. Nhưng phạm vi thật rộng
hơn nhiều so với "hai đơn thuê":

- Đơn thuê, giữ chỗ chờ thanh toán, bảo dưỡng, đăng kiểm, điều chuyển chi nhánh, chủ xe tự khoá —
  **tất cả** đều là khoá lịch và đều phải nằm chung một ràng buộc.
- **Tài xế** (BR-507) và **nhân viên giao xe** là tài nguyên tranh chấp cùng bản chất.
- **Khoảng đệm nằm bên trong khoảng khoá** (BR-109, BR-116), và nó **khác nhau theo gói thuê**:
  2 giờ cho gói ngày, 1 giờ cho gói giờ.

Kiểm tra kiểu "đọc xem có trống không rồi mới ghi" **luôn sai** dưới đồng thời. Khoá ở tầng ứng dụng
giảm xác suất nhưng không loại trừ. Giải pháp bắt buộc: **ràng buộc loại trừ của PostgreSQL** —
CSDL từ chối, không phải code từ chối. Xem ADR-0005.

### 3.2. Con người đóng vai cổng thanh toán

Giai đoạn này thanh toán thủ công (BR-213): nhân viên bấm "đã nhận tiền". Điều đó **không** làm bài
toán dễ hơn — nó làm khó hơn, vì con người bấm hai lần, bấm nhầm, và bấm lại khi mạng chậm.

Mọi thao tác ghi tiền cần đúng những thứ một cổng thanh toán thật cần: khoá idempotency, ghi audit,
chặn xác nhận lại. Áp dụng cho cả xác nhận hai phía khi bàn giao (BR-204, BR-205) và chuyển cọc khi
đổi xe (BR-309). Xem ADR-0011.

### 3.3. Đóng băng dữ liệu theo đơn

Ba quy tắc độc lập cùng nói một điều: BR-122 (giá), BR-208 (hoa hồng), BR-225 (tham số cấu hình).
Đơn phải giữ nguyên mọi giá trị tại thời điểm tạo, kể cả khi admin đổi cấu hình ngày hôm sau.

Đây không phải chi tiết nhỏ — nó là một **mẫu thiết kế xuyên suốt**. Đọc cấu hình lúc quyết toán
thay vì lúc tạo đơn là loại lỗi chỉ lộ ra khi đối soát tiền với đối tác, tức là muộn nhất có thể.
Xem ADR-0014.

### 3.4. Hồ sơ bằng chứng có giá trị pháp lý

Theo Nghị định 336/2025/NĐ-CP, biên bản bàn giao và giấy tờ khách là thứ quyết định công ty hay
khách trả tiền phạt nguội (BR-702). Kéo theo ba ràng buộc kỹ thuật:

- **Bất biến**: không sửa, không xoá, không ghi đè (BR-407).
- **Sống lâu hơn tài khoản**: khách xoá tài khoản thì hồ sơ đơn đã hoàn tất vẫn giữ (BR-123).
- **Truy vấn được theo thời điểm**: "ai lái xe X lúc 14:32 ngày 3/5" phải trả lời được sau nhiều
  tháng, trong vài giây (BR-701).

Xem ADR-0015.

### 3.5. Hai trục chính sách, không phải một

Hệ thống có **hai chiều phân loại** cùng ảnh hưởng tới hành vi:

| Trục | Giá trị | Ảnh hưởng |
|---|---|---|
| `ownership_type` | `COMPANY` \| `PARTNER` | Dòng tiền, hoa hồng, chi trả |
| `rental_type` | `HOURLY` \| `DAILY` \| `MONTHLY` | Giá, giới hạn km, khoảng đệm, nhiên liệu, phí trễ, phí tài xế |

Trục thứ hai chạm vào **sáu nhóm quy tắc khác nhau** (BR-114 → BR-118b). Nếu để `if (rental_type ==
HOURLY)` rải rác, sau vài tháng nó sẽ nằm ở hàng chục chỗ và không ai dám sửa.

Cả hai trục phải được phân giải thành **policy một lần ở biên use case**, không phân nhánh rải rác.
Xem ADR-0004.

### 3.6. Tìm kiếm kết hợp không gian và thời gian

Truy vấn trung tâm: *xe nào còn trống từ thứ Sáu tới Chủ nhật, trong bán kính 5 km, dưới X đồng?* —
lọc không gian, lọc thời gian, và sắp xếp cùng lúc.

Tự viết công thức khoảng cách trong SQL sẽ không dùng được index không gian và phải quét toàn bảng.
Dùng PostGIS. Xem ADR-0007.

Thêm một ràng buộc kiến trúc từ BR-112: **tìm kiếm không được biết xe thuộc ai.** Đây là ranh giới
cần giữ sạch, không chỉ là quy tắc nghiệp vụ.

### 3.7. Cấu hình có hiệu lực theo thời gian

BR-225 liệt kê 11 tham số kinh doanh phải chỉnh được mà không phát hành lại. Cộng với BR-218
(danh sách ngày lễ là dữ liệu, không phải code) và BR-230 (bậc chiết khấu).

Cấu hình ở đây **không phải một bảng key-value đơn giản**: mỗi tham số có hiệu lực theo khoảng thời
gian, và đơn đã tạo giữ giá trị cũ (§3.3). Thiết kế sai chỗ này thì mỗi lần đổi giá sẽ làm sai
những đơn đã đặt.

## 4. Toàn cảnh thành phần

```text
  customer-web        admin-web         mobile (React Native)
   React + TS         React + TS         iOS · Android
        │                  │                    │
        └──────────────────┴────────────────────┘
                           │ HTTPS · JSON · Bearer JWT
                    ┌──────▼──────┐
                    │  Keycloak   │  OIDC · PKCE · OTP qua SMS (tự viết)
                    └──────┬──────┘
                           │
              ┌────────────▼─────────────┐
              │      car-rental-api      │
              │  Spring Boot · 1 khối    │
              │  ~16 module nghiệp vụ    │
              └────────────┬─────────────┘
                           │
     ┌──────────┬──────────┼──────────┬──────────────┐
┌────▼─────┐┌───▼────┐┌────▼─────┐┌───▼─────┐┌───────▼──────┐
│PostgreSQL││ Redis  ││  MinIO   ││ Outbox  ││  Prometheus  │
│ +PostGIS ││ cache  ││ ảnh xe   ││ sự kiện ││  Grafana     │
│+btree_   ││ rate   ││ giấy tờ  ││ nội bộ  ││  Zipkin      │
│  gist    ││ limit  ││ biên bản ││         ││              │
└──────────┘└────────┘└──────────┘└─────────┘└──────────────┘
```

> **Redis không bao giờ là cơ chế bảo đảm chống trùng lịch.** Nó để cache kết quả tìm kiếm và giới
> hạn tần suất. Bảo đảm nằm ở ràng buộc CSDL (ADR-0005). Đây là cái bẫy phổ biến nhất: khoá Redis
> *trông có vẻ* chạy được lúc test rồi hỏng ở production khi khoá hết hạn sớm hoặc tiến trình chết
> giữa transaction.

## 5. Giai đoạn hiện tại

Chỉ chạy **local**, chưa build production. Điều này **không** cho phép bỏ qua transaction,
idempotency hay kỷ luật migration — đó là những thứ gần như không sửa được sau khi có dữ liệu thật.

Nó có nghĩa: cổng thanh toán, SMS, push, telematics dùng adapter mock hoặc thủ công;
docker-compose thay cho hạ tầng thật; chưa có CI/CD triển khai.
