# Frontend Guideline

> Nền: ADR-0010 (tách `customer-web` và `admin-web`).

## 1. Nền tảng
React + TypeScript. Hai ứng dụng riêng ở giai đoạn 1.

| App | Người dùng | Ưu tiên kỹ thuật |
|---|---|---|
| `customer-web` | Khách thuê, phần lớn là người lạ đến từ tìm kiếm | Tải nhanh, SEO, chạy tốt trên mạng di động |
| `admin-web` | Vận hành, kế toán, quản trị | Màn hình dày dữ liệu, bộ lọc nhiều cột |

## 2. Cấu trúc
```text
src/
├── app/                # routing, provider, layout gốc
├── features/<domain>/  # nhóm theo miền nghiệp vụ
│   ├── api/            # hàm gọi API, dùng kiểu sinh từ OpenAPI
│   ├── components/
│   ├── hooks/
│   └── types.ts
├── shared/             # design system, tiện ích, HTTP client
└── lib/                # auth, cấu hình
```
Miền ở `customer-web`: `search` · `vehicle-detail` · `quote` · `booking` · `payment` · `trips` · `profile`.

## 3. TypeScript
- `strict: true`. Không `any` trong code nghiệp vụ.
- **Kiểu API sinh từ đặc tả OpenAPI của backend, không viết tay.** Viết tay nghĩa là frontend và
  backend sẽ lệch nhau âm thầm, và lỗi chỉ lộ ra ở runtime tại nhà khách hàng.
- Enum từ backend: **xử lý được giá trị lạ**. Backend có thể thêm trạng thái mới (api-guideline §4)
  và giao diện không được vỡ vì điều đó.

## 4. Tích hợp API
- Một HTTP client dùng chung, tự gắn bearer token và làm mới khi hết hạn.
- Bọc `ApiResponse<T>` một lần, không rải kiểm tra `success` khắp nơi.
- Ánh xạ `error.code` sang thông báo tiếng Việt qua **một bảng tập trung**.

### 409 phải được xử lý tử tế
Đây là mã lỗi đặc trưng của sản phẩm. Khi khách bấm đặt và nhận `VEHICLE_NOT_AVAILABLE`, **đừng hiện
"Đã có lỗi xảy ra"**. Nói đúng chuyện đã xảy ra — xe vừa được người khác đặt — và đưa khách trở lại
danh sách với gợi ý xe tương tự cùng khung giờ.

Đây là tình huống **bình thường** của việc phân bổ tài nguyên khan hiếm, không phải sự cố kỹ thuật.

### Đồng hồ đếm ngược cho thời hạn giữ chỗ
Giữ chỗ chỉ có 1 tiếng (BR-103). Màn hình thanh toán phải hiện đồng hồ đếm ngược và xử lý rõ ràng
khi hết hạn — không để khách trả tiền cho một chỗ đã mất.

## 5. Hiển thị giá phải tách dòng
Báo giá trả về bảng chi tiết (api-guideline §7). Giao diện **hiện đúng các dòng đó**: tiền thuê theo
loại ngày, phí tài xế, phí giao xe, **phí bảo hiểm dòng riêng**, giảm giá, tổng.

Gộp thành một con số là đi ngược yêu cầu minh bạch của nghiệp vụ (BR-231, BR-419), và làm mất điểm
bán hàng mạnh nhất — khách không thấy mình được bảo hiểm.

## 6. Auth
Authorization Code + PKCE với Keycloak. Mỗi app là một client riêng với redirect URI riêng.

## 7. Không đặt quy tắc nghiệp vụ ở frontend
Frontend được **hiển thị** quy tắc — ví dụ ẩn nút huỷ khi quá hạn huỷ — nhưng server phải kiểm lại
toàn bộ. Mọi quy tắc chỉ tồn tại ở frontend đều là quy tắc không tồn tại.

## 8. Bản đồ
`customer-web` cần bản đồ để chọn điểm nhận xe và xem xe quanh mình.
⚠️ Thư viện bản đồ và nhà cung cấp tile **chưa chốt** — cần quyết trước khi làm màn hình tìm kiếm,
vì nó ảnh hưởng chi phí vận hành khi lên production.
