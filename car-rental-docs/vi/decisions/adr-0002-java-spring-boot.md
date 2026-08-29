# ADR-0002: Java + Spring Boot

- Trạng thái: ACCEPTED · 27/08/2026 · Tech Owner

## Quyết định
Backend viết bằng Java với Spring Boot.

## Lý do
Đội đã vận hành thành công stack này ở dự án gym-platform. Với một sản phẩm mà rủi ro nằm ở nghiệp
vụ chứ không ở công nghệ, chọn thứ đã thuần tay là đúng — toàn bộ ngân sách học hỏi nên dồn vào
149 quy tắc nghiệp vụ, không dồn vào ngôn ngữ.

Thêm một lý do cụ thể: ADR-0006 cần viết một Authenticator cho Keycloak, và Keycloak SPI là Java.
Cùng ngôn ngữ nghĩa là cùng đội làm được, không cần kỹ năng thứ hai.

## Hệ quả
- Phiên bản Java và Spring Boot chốt ở `development-guideline.md`.
- Convention kế thừa từ gym-platform, chỉnh cho nghiệp vụ thuê xe.
