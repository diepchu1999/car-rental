# Phân tích yêu cầu — v6

- **Nguồn:** phỏng vấn Tech Owner, 22–24/08/2026 (6 vòng).
- **Người soạn:** Chief Architect.
- **Ký hiệu:** ✅ đã chốt · 🟡 Chief Architect đề xuất, chờ duyệt · ⚠️ chưa chốt / cần làm rõ.

---

## 0. Thuật ngữ tiền — ĐỌC TRƯỚC

Tech Owner đã chỉ ra Chief Architect dùng lẫn hai khái niệm ở v4. Chốt lại rõ ràng, và **mọi tài
liệu, code, API từ đây về sau dùng đúng hai từ này**:

| Thuật ngữ | Tiếng Anh | Bản chất | Số phận cuối cùng |
|---|---|---|---|
| **Cọc** | `deposit` | **Trả trước một phần tiền thuê** (30%, dài hạn 40%) | **Không hoàn** — được trừ vào tiền thuê |
| **Thế chấp** | `collateral` | **Tài sản bảo đảm** (tiền mặt hoặc xe máy + cà vẹt) | **Hoàn lại 100% ngay sau khi trả xe** |

Hai thứ này khác nhau về mọi mặt: cọc là doanh thu tương lai, thế chấp là tài sản giữ hộ.
Ghi sổ khác nhau, hoàn trả khác nhau, và nhầm lẫn giữa chúng dẫn tới sai sót tiền.

Khoản thứ ba, tách bạch: **phần còn lại** (`balance`) — 70% tiền thuê trả khi nhận xe.

## 1. Bối cảnh

Startup **chưa có xe, chưa có khách, chưa vận hành**. Xây phần mềm trước, "cứ build như app thật".

Mọi quy tắc đang được *phát minh*, không phải *ghi lại* — thiết kế phải chịu được việc chúng thay đổi.
Ràng buộc thật của giai đoạn này là **tốc độ tới giao dịch thật đầu tiên**, không phải hiệu năng.

## 2. Mô hình kinh doanh

| Dòng doanh thu | Nguồn | Giai đoạn |
|---|---|---|
| Cho thuê xe của chính công ty | Toàn bộ tiền thuê | 1 |
| Hoa hồng trên xe đối tác | %, admin cấu hình được | 2 |

| Nguồn cung | Ai sở hữu | Dòng tiền |
|---|---|---|
| Xe công ty | Công ty | Thu toàn bộ |
| Đối tác **cá nhân** | Người dân có xe rảnh | Trả chủ xe, giữ hoa hồng |
| Đối tác **doanh nghiệp** | Công ty cho thuê xe khác | Trả đối tác, giữ hoa hồng |

### 2.1. Hai trục phân loại ✅
- **`ownership_type`** = `COMPANY` | `PARTNER` — phân biệt theo **dòng tiền**.
- **`partner_type`** = `INDIVIDUAL` | `BUSINESS` — phân biệt theo **hợp đồng, kênh, cách bố trí tài xế**.

### 2.2. Hoa hồng đổi theo giai đoạn ⇒ đóng băng theo đơn ✅
Hoa hồng là **dãy mức có hiệu lực theo thời gian**. Mỗi đơn **đóng băng** mức tại thời điểm tạo.
Nếu đọc từ bảng cấu hình lúc chi trả, mỗi lần admin đổi mức thì đơn cũ chưa chi trả bị tính lại
theo mức mới — sai, và chỉ lộ ra khi đối soát với đối tác.

### 2.3. Dòng tiền ✅

Khách trả tiền thuê theo **hai chặng**:

```text
Chặng 1 — khi đặt xe:     CỌC 30% (dài hạn 40%)  →  nền tảng giữ
Chặng 2 — khi nhận xe:    PHẦN CÒN LẠI 70%       →  khách chọn:
                                                      (a) trả trực tiếp cho chủ xe, HOẶC
                                                      (b) chuyển khoản qua app
```

**Hoa hồng của nền tảng được trừ từ khoản cọc đang giữ.** Phần cọc còn lại sau khi trừ hoa hồng
được chi trả cho chủ xe.

Ví dụ đơn 10 triệu, hoa hồng 10%:

| Khoản | Số tiền | Ai giữ cuối cùng |
|---|---|---|
| Cọc khách trả khi đặt | 3.000.000 | nền tảng thu |
| → hoa hồng nền tảng | 1.000.000 | **nền tảng giữ** |
| → phần còn lại của cọc | 2.000.000 | chi trả cho chủ xe |
| Phần còn lại khách trả khi nhận xe | 7.000.000 | chủ xe (trực tiếp hoặc qua app) |
| **Chủ xe nhận tổng** | **9.000.000** | |

**Hai hệ quả kỹ thuật:**

1. **Bất biến bắt buộc: hoa hồng không được vượt tỷ lệ cọc.** Nếu admin đặt hoa hồng 35% trong khi
   cọc là 30%, mô hình vỡ — nền tảng không đủ tiền giữ để trừ. Phải chặn ngay lúc admin cấu hình,
   không phải lúc chi trả.
2. **Đường (a) — khách trả trực tiếp cho chủ xe — nền tảng không quan sát được.**
   Đã chốt: **chủ xe xác nhận trên app là đã nhận đủ tiền, và chuyến bắt đầu từ đó.** ✅

   Với xe công ty, "chủ xe" là `BRANCH_STAFF` hoặc `DELIVERY_STAFF` — cùng một cơ chế, khác vai.

   🟡 **Đề xuất bổ sung: khách xác nhận cùng lúc.** Khoảnh khắc này hai bên đã đứng cạnh nhau và
   đang ký biên bản bàn giao, nên thêm một dòng "đã thanh toán đủ phần còn lại" vào chính biên bản
   đó gần như không tốn gì. Không có xác nhận của khách, khi tranh chấp số tiền đã trả thì hệ thống
   chỉ có lời của một bên — đúng cùng lý do đã áp dụng cho thế chấp tiền mặt (§7).

   ⚠️ **Rủi ro vận hành cần lưu ý:** vì chuyến chỉ bắt đầu được khi chủ xe xác nhận, chủ xe quên
   thao tác sẽ chặn khách ngay lúc khách đang đứng chờ lấy xe. Cần nhắc chủ xe chủ động, và cho
   `BRANCH_MANAGER` quyền xác nhận thay khi có sự cố.

## 3. Chi nhánh ✅

Xe công ty đỗ theo chi nhánh. Mỗi chi nhánh có **một địa điểm cụ thể** và **một quản lý chi nhánh**.
**Mỗi xe thuộc đúng một chi nhánh; một chi nhánh có nhiều xe.**

| Quy tắc | Chi tiết |
|---|---|
| Trả xe | **Bắt buộc trả đúng chi nhánh đã nhận** — trả sai phát sinh chi phí di chuyển |
| Quyền hạn | Chi nhánh **không** can thiệp hợp đồng của chi nhánh khác |
| Ngoại lệ | Chỉ vai cao nhất mới xem/xử lý xuyên chi nhánh |
| Hỗ trợ chéo | Có luồng **yêu cầu hỗ trợ** giữa chi nhánh (ví dụ nhờ hỗ trợ bảo dưỡng) |
| Huỷ đơn xe công ty | Cần **quản lý chi nhánh duyệt** + lý do chính đáng |

Xe đối tác **không** thuộc chi nhánh — vị trí là nơi chủ xe hẹn.

Chi nhánh ảnh hưởng tới: vị trí xe khi tìm kiếm · khoảng cách tính phí giao xe · nhân sự thuộc chi
nhánh nào · ranh giới phân quyền · quỹ tiền mặt · nơi bắt buộc trả xe.

### 3.1. Điều chuyển xe giữa chi nhánh ✅

Quy trình: **quản lý chi nhánh yêu cầu → `OPERATIONS_MANAGER` duyệt**. `ADMIN` duyệt được tất cả.
**Chi nhánh nhận phải xác nhận đã nhận xe** — nếu không, xe sẽ nằm ở trạng thái "đang di chuyển"
vĩnh viễn khi ai đó quên thao tác, và không ai biết chiếc xe đang ở đâu.

**Trong lúc xe đang di chuyển, nó không cho thuê được** — điều chuyển phải khoá lịch xe giống lịch
bảo dưỡng. Điều chuyển đổi chi nhánh của xe, nên phải lưu lịch sử để sau còn trả lời được "lúc đó
xe thuộc chi nhánh nào".

## 4. Vai người dùng ✅

Tech Owner: **tên vai dùng tiếng Anh.**

| Vai | Mô tả | Kênh | Giai đoạn |
|---|---|---|---|
| `CUSTOMER` | Khách thuê | Mobile + Web | 1 |
| `BRANCH_STAFF` | Nhân viên vận hành chi nhánh | Web | 1 |
| `BRANCH_MANAGER` | Quản lý chi nhánh | Web | 1 |
| `DELIVERY_STAFF` | Nhân viên giao xe | Web + Mobile | 1 |
| `DRIVER` | Tài xế là nhân viên | Web + Mobile | 1 |
| `ACCOUNTANT` | Kế toán | Web | 1 |
| `OPERATIONS_MANAGER` | Quản lý vận hành — duyệt điều chuyển xe | Web | 1 |
| `ADMIN` | Vai cao nhất, toàn quyền | Web | 1 |
| `HOST` | Đối tác cá nhân | Mobile + Web | 2 |
| `BUSINESS_PARTNER` | Đối tác doanh nghiệp | Web riêng cho doanh nghiệp | 3 |
| `BUSINESS_PARTNER_STAFF` | Tài khoản con của đối tác doanh nghiệp | Web doanh nghiệp | 3 |
| `FREELANCE_DRIVER` | Tài xế tự do | Mobile | 3 |

🟡 Mobile là **một app, hai chế độ** (`CUSTOMER` / `HOST`), không phải hai app.

## 5. Luồng thuê xe ✅

```text
Khách mở web/app
  → tìm xe gần vị trí, lọc nhiều tiêu chí
  → chọn tự lái hoặc có tài xế
  → chọn xe (không phân biệt xe công ty hay đối tác)
  → đặt xe, trả CỌC 30%
  → hệ thống giữ chỗ, hạn thanh toán 1 tiếng
  → nhận xe: trả PHẦN CÒN LẠI + đặt THẾ CHẤP (nếu có yêu cầu)
  → dùng xe
  → trả xe ĐÚNG chi nhánh đã nhận → hoàn THẾ CHẤP
```

## 6. Tài xế — bốn mô hình cung ứng, một khái niệm chung ✅

### 6.1. Bốn cách bố trí người lái

| Loại xe | Ai lái | Ai bố trí | Giai đoạn |
|---|---|---|---|
| Xe công ty | `DRIVER` (nhân viên) | Công ty phân công | 1 |
| Đối tác **cá nhân** | **Chính chủ xe tự lái** | Chủ xe tự lo | 2 |
| Đối tác **doanh nghiệp** | Tài xế của họ | **Họ tự điều phối** | 3 |
| (GĐ3) | `FREELANCE_DRIVER` | Tài xế tự nhận chuyến | 3 |

### 6.2. Khái niệm chung: **người lái được khai báo cho chuyến**

> Trước khi chuyến khởi hành, phải có **đúng một người lái được khai báo**, có danh tính và giấy phép
> lái xe đã xác minh, và **khách thuê nhìn thấy được**.

Bốn mô hình khác nhau ở **cách gán**, giống hệt nhau ở **cái hệ thống cần biết**. Đây là điểm mấu
chốt để không phải viết bốn hệ thống.

### 6.3. Bất biến bắt buộc

1. **Không có người lái khai báo ⇒ chuyến không được khởi hành.** Ràng buộc pháp lý, không phải
   tiện ích: nếu xảy ra chuyện, không ai chứng minh được ai đang lái (§11).
2. **GPLX phải được xác minh trước khi khởi hành**, không phải sau.
3. **Đổi tài xế chỉ được phép trước giờ khởi hành.** Sau đó là dữ liệu bất biến.
4. **Mọi lần đổi tài xế đều ghi lịch sử và báo cho khách.** Khách đặt xe có tài xế là đang tin vào
   một người cụ thể; đổi người mà im lặng là phá vỡ chính điều khiến họ yên tâm.
5. **Một tài xế không được nhận hai chuyến chồng giờ** — cùng bản chất tranh chấp như chiếc xe.

### 6.4. So với Grab
Đối tác cá nhân **đơn giản hơn Grab nhiều**: chủ xe đã gắn sẵn với chính xe của họ cho khoảng thời
gian đã đặt. Không cần ghép động, không cần đấu giá chuyến — chỉ cần **khai báo và xác minh**.
`FREELANCE_DRIVER` (GĐ3) mới thật sự là mô hình Grab, và đó là lý do nó nặng.

## 7. Thuê dài hạn theo tháng ✅

| Mục | Quy tắc |
|---|---|
| Cọc | **40%** |
| Thế chấp | **Bắt buộc**, kể cả xe công ty |
| Hình thức thanh toán | **Chuyển khoản** (Tech Owner đã duyệt, bỏ tiền mặt) |
| Hợp đồng | Kỹ hơn thuê ngày |

### 7.1. Mức thế chấp ✅

Đề xuất cũ của Chief Architect (1 tháng tiền thuê) **đã bị rút lại** — Tech Owner phản biện đúng
rằng nó bắt khách bỏ ra gấp đôi tiền thuê. Lỗi nằm ở chỗ định cỡ thế chấp **theo tiền thuê thay vì
theo rủi ro thật**.

Rủi ro thật, sau khi đã có bảo hiểm theo chuyến (§12):

| Rủi ro | Ai gánh | Thế chấp có cần che |
|---|---|---|
| Hư hỏng, va chạm | Bảo hiểm; khách chịu tối đa 2 triệu | **Không** — bảo hiểm đã chặn |
| Phạt nguội | Khách | Vài triệu |
| Trễ giờ, vượt km, nhiên liệu, vệ sinh | Khách | Vài triệu |
| Không trả xe / mất xe | Bảo hiểm + pháp lý | **Không thế chấp nào che nổi** |

Phần thế chấp thật sự cần che chỉ là nhóm giữa, **và nhóm đó không tỷ lệ với tiền thuê** — thuê một
tháng không làm tiền phạt nguội tăng gấp mười.

**Tech Owner chốt: phương án B — ba bậc theo phân khúc xe.**

| Phân khúc | Thế chấp |
|---|---|
| Phổ thông | 15.000.000đ |
| Trung cấp | 25.000.000đ |
| Cao cấp | 40.000.000đ |

**Cho phép thay thế bằng xe máy + cà vẹt** giá trị tương đương, như Mioto — giảm rào cản đáng kể cho
khách không sẵn tiền mặt lớn.

#### 🟡 Phân khúc xác định thế nào — đề xuất chờ duyệt (N23)

"Phổ thông / trung cấp / cao cấp" cần một định nghĩa máy đọc được. Hai cách:

**Cách 1 — suy ra từ giá thuê ngày** (Chief Architect đề xuất):

| Giá thuê ngày | Phân khúc | Thế chấp |
|---|---|---|
| < 1.000.000đ | Phổ thông | 15 triệu |
| 1.000.000 – 2.000.000đ | Trung cấp | 25 triệu |
| > 2.000.000đ | Cao cấp | 40 triệu |

**Cách 2 — gắn nhãn phân khúc thủ công cho từng xe** khi tạo hồ sơ xe.

Đề xuất **Cách 1**, cùng lý do đã dùng cho trần phụ phí km (§10.3): không cần ai phân loại từng xe,
không cần duyệt lại khi thêm dòng xe mới, và tự co giãn. Giá thuê ngày vốn đã phản ánh phân khúc —
không ai cho thuê Mercedes giá Morning.

Cách 2 chỉ hơn khi bạn muốn đặt thế chấp cao cho một chiếc xe cụ thể vì lý do riêng (xe hiếm, phụ
tùng khó kiếm). Có thể thêm sau như một ngoại lệ ghi đè, không cần làm ngay.

## 8. Thanh toán — thủ công trước ✅

**Giai đoạn local:** khách chuyển khoản; **nhân viên đối chiếu và xác nhận bằng tay**.
**Về sau (chưa làm, chỉ chừa chỗ):** ví điện tử · đọc biến động số dư để tự khớp · **VietQR**.

**Khách phải đăng ký sẵn số tài khoản ngân hàng trong app** để nhận hoàn tiền.

### Hệ quả kiến trúc
"Nhân viên xác nhận đã nhận tiền" là **thao tác ghi tiền do người thực hiện**, cần đủ mọi thứ một
callback tự động cần: idempotency, ghi audit, chặn xác nhận hai lần. **Thủ công không có nghĩa là
lỏng** — con người đang đóng vai cổng thanh toán, mà cổng nào cũng có thể gửi tín hiệu trùng.

Điều này áp dụng cho **cả bước chủ xe xác nhận đã nhận phần còn lại** (§2.3).

## 9. Điều kiện thuê tự lái ✅

- **Không giới hạn độ tuổi** (thực tế vẫn bị chặn dưới bởi chính GPLX).
- **Bắt buộc xác minh giấy phép lái xe.**
- **Hợp đồng ghi rõ: người thuê phải là người lái.**

Điều khoản này cần một **bước kiểm tra lúc bàn giao** — đối chiếu người đứng trước mặt với giấy tờ
trong đơn. Chỉ ghi trong hợp đồng mà không kiểm tra thì không có hiệu lực thực tế, và §11 cho thấy
nó còn là bằng chứng pháp lý.

## 10. Chính sách đã chốt

### 10.1. Thế chấp ✅

| Trường hợp | Thế chấp |
|---|---|
| Xe công ty, thuê ngày | **Không yêu cầu.** Chỉ cần hợp đồng + giấy tờ hợp pháp |
| Xe công ty, thuê dài hạn | **Bắt buộc** — ba bậc theo phân khúc (§7.1) |
| Xe đối tác | **Do đối tác tự quyết** |

**Hoàn thế chấp: ngay sau khi khách trả xe.** ✅

🟡 "Miễn thế chấp" là **lợi thế cạnh tranh thật** của xe công ty — khách ngại thế chấp sẽ chọn đúng
loại xe mang lại toàn bộ doanh thu cho bạn. Nên lộ rõ trên giao diện tìm kiếm.

### 10.2. Giới hạn km — tính gộp cả chuyến ✅

| Loại xe | Giới hạn |
|---|---|
| Xe công ty | 300 km/ngày, **gộp toàn chuyến** |
| Xe đối tác | Đối tác tự đặt, **cùng cách tính gộp** |

Đọc ODO lúc giao và lúc nhận, lấy hiệu, so với `giới_hạn_ngày × số_ngày_thuê`.
Một phép trừ, một phép so sánh, kiểm tra đúng một lần lúc trả xe.

### 10.3. Trần phụ phí vượt km ✅

```text
giá trị ngầm mỗi km  =  giá thuê 1 ngày ÷ giới hạn km 1 ngày
trần phụ phí vượt    =  2 × giá trị ngầm mỗi km
```

**Chặn ngay lúc đối tác đăng tin**, không phải lúc tính tiền.

### 10.4. Cọc ✅
**30%** giá trị chuyến; thuê dài hạn **40%**. Không hoàn — trừ vào tiền thuê.
Thời hạn giữ chỗ: **1 tiếng** kể từ lúc bấm đặt; quá hạn tự nhả xe.

### 10.5. Huỷ đơn ✅

**Khách huỷ** — thang ba mốc theo giờ nhận xe:

| Thời điểm huỷ | Hoàn cọc |
|---|---|
| Trước **1 tuần** | **100%** |
| **1 tuần → 24 giờ** | **60%** (giữ lại 40% phí huỷ — theo mức Mioto dùng) |
| Trong vòng **24 giờ** | **Mất toàn bộ cọc** |

**Đối tác huỷ:** bị **trừ sao đánh giá** **và** phải **bồi thường** khách (theo Mioto).
Khách hoàn cọc **100%**.

**Công ty huỷ (xe công ty):** cần **quản lý chi nhánh duyệt** + lý do chính đáng. Khách hoàn **100%**.

**Hoàn tiền:** trong vòng **3 ngày làm việc**, về **số tài khoản khách đã đăng ký trong app**.

### 10.6. Hoa hồng ✅
Admin cấu hình được, đổi theo giai đoạn kinh tế; mỗi đơn đóng băng mức tại thời điểm tạo (§2.2).
**Trừ từ khoản cọc nền tảng đang giữ** (§2.3). **Không được vượt tỷ lệ cọc.**

### 10.7. Đổi xe giữ nguyên cọc — nghiệp vụ giữ khách ✅

Khi khách huỷ, nhân viên **gọi điện hỏi lý do và tư vấn xe khác**. Nếu khách đồng ý, nhân viên
**chuyển cọc sang xe mới**:

| Tình huống | Xử lý |
|---|---|
| Xe mới cọc **cao hơn** | Khách bù phần chênh |
| Xe mới cọc **thấp hơn** | **Phần dư trừ vào phần thanh toán khi nhận xe** |
| Xe mới cọc **bằng** | Không phát sinh |

🟡 Mioto không có nghiệp vụ này. Huỷ đơn là khoảnh khắc khách đã có nhu cầu thật và đã trả tiền —
cứu một đơn ở đây rẻ hơn nhiều so với tìm khách mới.

**Kỷ luật bắt buộc:**
- Chuyển cọc phải **idempotent** — nhân viên bấm hai lần không nhân đôi.
- **Audit đầy đủ**: ai chuyển, từ đơn nào sang đơn nào, chênh lệch bao nhiêu.
- Đơn cũ mang trạng thái riêng **"đã chuyển đổi"**, KHÔNG phải "đã huỷ". Ghi là huỷ thì tỷ lệ huỷ
  trong báo cáo sẽ sai, và mất luôn cách đo hiệu quả của chính nghiệp vụ này.
- **Giữ chỗ được trên xe mới trước khi nhả xe cũ**, nếu không khách mất cả hai.

## 11. Phạt nguội giao thông ✅

### 11.1. Khung pháp lý
Theo Nghị định 336/2025/NĐ-CP, thông báo phạt nguội gửi cho **chủ xe** — tức là công ty bạn với mọi
xe công ty. Chủ xe có nghĩa vụ hợp tác xác định người điều khiển. Nếu **chứng minh được** người vi
phạm là khách thuê — qua **hợp đồng thuê xe, CCCD, giấy phép lái xe, biên bản bàn giao** — thì chủ
xe **không bị xử phạt**.

### 11.2. Hệ quả: biên bản bàn giao là bằng chứng pháp lý
Bộ hồ sơ trên là **thứ quyết định ai trả tiền phạt**. Thiếu một mảnh, công ty chịu.

- **Trả lời được "ai đang lái xe X lúc 14:32 ngày 3/5" nhiều tháng sau, trong vài giây.**
- **Hồ sơ bằng chứng bất biến**: ảnh CCCD, GPLX, biên bản bàn giao có mốc thời gian và ODO, chữ ký.
  Không sửa, không xoá, không ghi đè.
- Với chuyến **có tài xế**, người chịu trách nhiệm là **người lái được khai báo** (§6.2), không phải
  người thuê. Lý do nữa để §6.3 không được nới lỏng.

### 11.3. Cách xử lý đã chốt ✅

- **Thế chấp hoàn ngay sau khi trả xe.** Không giữ lại chờ phạt nguội.
- Khi có thông báo phạt: **dùng bộ bằng chứng làm việc với cơ quan chức năng và với khách.**
- Xe công ty gắn **GPS + camera hành trình** — hỗ trợ chứng minh.

**Rủi ro đã được Tech Owner chấp nhận, ghi lại để không ai quên:** với xe công ty thuê ngày (không
có thế chấp) và thế chấp hoàn ngay khi trả xe, khi giấy phạt về thì **không còn khoản tiền nào của
khách đang nằm trong tay công ty**. Việc thu lại phụ thuộc hoàn toàn vào bộ bằng chứng và thiện chí
của khách. Đây là đánh đổi có chủ đích: ưu tiên trải nghiệm khách hơn là bịt kín rủi ro.

🟡 **Một giảm nhẹ không tốn gì và không làm chậm việc hoàn thế chấp:** tra cứu phạt nguội theo biển
số **ngay tại thời điểm nhận lại xe**. Nếu đã có vi phạm trên hệ thống thì xử lý luôn tại chỗ, khi
khách còn đứng đó. Không bắt được vi phạm chưa kịp cập nhật, nhưng bắt được phần đã có, và không
trì hoãn việc trả thế chấp một phút nào.

🟡 Ghi vi phạm vào **hồ sơ khách**; khách tái phạm nhiều lần thì hạn chế thuê.

## 12. Bảo hiểm ✅

Tech Owner: **làm giống Mioto.**

| Mục | Quy tắc |
|---|---|
| Hình thức | **Mua bảo hiểm theo chuyến** |
| Mức miễn thường khách chịu | **Tối đa 2.000.000đ mỗi sự cố** |
| Xe đối tác | **Không bắt buộc** bảo hiểm thân vỏ riêng; chỉ cần **đầy đủ giấy tờ theo luật** |

Tham khảo Mioto: gói bảo hiểm thuê xe tự lái từ MIC & DBV (VNI); người trên xe được bảo hiểm tối đa
300 triệu/người.

### 12.1. Cơ chế cốt lõi là **mức miễn thường**
Khách biết trước rằng dù đâm hỏng thế nào cũng chỉ mất tối đa 2 triệu. **Chính con số trần đó là thứ
khiến người ta dám thuê xe tự lái.** Đây là đòn bẩy chuyển đổi, không chỉ là chi phí.

### 12.2. Phân định hai loại bảo hiểm ✅
Tech Owner đã xác nhận:
- **TNDS bắt buộc** — theo luật Việt Nam, mọi ô tô đều phải có. Đã nằm trong "giấy tờ hợp lệ" của xe.
- **Thân vỏ** — không bắt buộc với xe đối tác. **Nền tảng mua theo chuyến.**

## 13. Phạm vi theo giai đoạn 🟡

| | Giai đoạn 1 | Giai đoạn 2 | Giai đoạn 3 |
|---|---|---|---|
| Nguồn xe | Xe công ty (theo chi nhánh) | + Đối tác cá nhân | + Đối tác doanh nghiệp |
| Hình thức | Tự lái + có tài xế + dài hạn | như GĐ1 | như GĐ2 |
| Tài xế | `DRIVER` nhân viên | + Chủ xe tự lái | + Đối tác tự điều phối, + `FREELANCE_DRIVER` |
| Nhận xe | Tại chi nhánh + công ty giao | + Chủ xe tự giao | như GĐ2 |
| Tiền | Cọc, phần còn lại, thế chấp, hoàn, đổi xe giữ cọc | + Chi trả đối tác, hoa hồng, bồi thường huỷ | + Hoá đơn VAT, tài khoản con |
| Kênh | Web + Mobile (khách) | + Mobile (`HOST`) | + Web doanh nghiệp |
| Phạt nguội, bảo hiểm, GPS | Có từ GĐ1 | như GĐ1 | như GĐ1 |

## 14. Quyết định kỹ thuật đã chốt ✅

| Mục | Quyết định |
|---|---|
| Mobile | **React Native** (iOS + Android), một app hai chế độ 🟡 |
| Thanh toán GĐ local | Thủ công, nhân viên xác nhận |
| Vị trí xe công ty | Theo chi nhánh |
| Thiết bị trên xe công ty | GPS + camera hành trình |
| Tên vai | **Tiếng Anh** |

## 15. Câu hỏi còn mở

| Mã | Câu hỏi | Chặn |
|---|---|---|
| **N23** | Phân khúc xe xác định bằng cách nào — suy ra từ giá thuê ngày (đề xuất) hay gắn nhãn thủ công? §7.1 | Chỉ nhánh thuê dài hạn |
| **N24** | Duyệt việc khách xác nhận cùng lúc với chủ xe khi thanh toán phần còn lại? §2.3 | Chỉ khâu bàn giao |

**Không câu nào chặn bộ business rules cho giai đoạn 1.** Cả hai đều là chi tiết hiện thực của
những quyết định đã chốt, và có thể trả lời song song trong lúc viết rules.

---

## Nguồn tham khảo ngoài

- Nghị định 336/2025/NĐ-CP về xử phạt vi phạm hành chính lĩnh vực giao thông đường bộ.
- mioto.vn — Quy chế hoạt động, trang Bảo hiểm.
- Bài hướng dẫn thứ cấp về đặt xe và phí dịch vụ Mioto (độ tin cậy vừa).
