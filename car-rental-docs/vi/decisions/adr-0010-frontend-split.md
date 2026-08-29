# ADR-0010: Tách `customer-web` và `admin-web`

- Trạng thái: ACCEPTED · 27/08/2026 · Tech Owner

## Quyết định
Giai đoạn 1 có hai ứng dụng web React + TypeScript riêng biệt:
- `customer-web` — tìm và đặt xe.
- `admin-web` — vận hành, kế toán, quản trị.

`host-web` để tới giai đoạn 2, khi có đối tác thật.

## Lý do
Hai ứng dụng có yêu cầu đối nghịch. `customer-web` phục vụ **người lạ đến từ tìm kiếm**, cần tải
nhanh trên mạng di động và cần SEO. `admin-web` phục vụ người đã đăng nhập, cần màn hình dày dữ liệu
với bộ lọc nhiều cột, và không quan tâm SEO chút nào.

Nhồi chung một bundle thì trang tìm xe phải kéo theo toàn bộ màn hình vận hành — cả hai đều tệ.

Không làm sẵn `host-web` ở GĐ1 vì GĐ1 chưa có đối tác nào; xây một ứng dụng chưa ai dùng là công sức
đợi mục.

## Hệ quả
- Cần thư viện dùng chung: design system, client API sinh từ OpenAPI, tiện ích auth.
- Mỗi app là một Keycloak client riêng với redirect URI riêng.
- 409 (trùng lịch) phải được `customer-web` xử lý tử tế — nêu đúng chuyện đã xảy ra và gợi ý xe
  tương tự, không hiện "đã có lỗi xảy ra" (ADR-0005).
