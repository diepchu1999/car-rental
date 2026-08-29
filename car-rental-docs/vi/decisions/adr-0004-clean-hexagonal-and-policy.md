# ADR-0004: Clean/Hexagonal Architecture, và phân giải policy cho hai trục phân loại

- Trạng thái: ACCEPTED · 27/08/2026 · Tech Owner

## Quyết định
Mỗi module tuân thủ cấu trúc `domain / application / adapter`, phụ thuộc luôn hướng vào trong.

- `domain` — thuần Java. Không Spring, không SQL, không HTTP, không Jackson.
- `application` — use case và **định nghĩa** port. Không biết công nghệ.
- `adapter` — **hiện thực** port. Chỗ duy nhất biết Spring, JDBC, S3, Keycloak.

## Phần riêng của dự án này: hai trục phân loại

Hệ thống có **hai** chiều phân loại cùng ảnh hưởng tới hành vi, không phải một:

| Trục | Giá trị | Chạm vào |
|---|---|---|
| `ownership_type` | `COMPANY` \| `PARTNER` | Dòng tiền, hoa hồng, chi trả |
| `rental_type` | `HOURLY` \| `DAILY` \| `MONTHLY` | Giá · giới hạn km · khoảng đệm · nhiên liệu · phí trễ · phí tài xế |

Trục thứ hai nguy hiểm hơn vì nó chạm sáu nhóm quy tắc (BR-114 → BR-118b). Để `if (rental_type ==
HOURLY)` rải rác thì sau vài tháng nó nằm ở hàng chục chỗ, và không ai dám sửa vì không biết đã sót
chỗ nào.

**Quy tắc bắt buộc:** cả hai trục được phân giải thành **policy đúng một lần ở biên use case**.
Chỉ các lớp `*PolicyResolver` được phép rẽ nhánh trên hai enum này. `domain` nhận policy đã phân
giải và không tự hỏi đơn thuộc loại nào.

Các họ policy dự kiến: giá · giới hạn km · khoảng đệm · chính sách nhiên liệu · phí trả trễ ·
phí tài xế · dòng tiền.

## Lý do
Nhiều loại client (2 web + mobile) và nhiều hạ tầng sẽ thay thế (thanh toán thủ công → tự động,
mock SMS → thật) đúng là hình dạng bài toán Hexagonal giải quyết.

## Làm rõ 21/09/2026 — `application` được dùng gì của Spring

Câu "`application` … không biết công nghệ" ở trên quá mơ hồ, và code Task 4 đã đi lệch khỏi nó mà
không ai nhận ra. Đây **không phải đổi quyết định** — Hexagonal vẫn nguyên — mà là nói chính xác
ranh giới vốn chỉ được nói chung chung.

`application` **được phép** dùng Spring cho đúng hai việc:
- **Tiêm phụ thuộc**: `@Service`, `@Component`, `@Qualifier`
- **Ranh giới transaction**: `@Transactional`

Đẩy hai thứ này ra khỏi `application` để giữ chữ nghĩa sẽ tốn nhiều nghi thức mà không đổi được
điều gì thật: ranh giới transaction vốn thuộc về use case, đặt nó ở nơi khác là đặt sai chỗ.

`application` **không được** dùng:

| Cấm | Vì sao |
|---|---|
| `org.springframework.web.*`, `org.springframework.http.*`, `jakarta.servlet.*` | HTTP là chi tiết của adapter vào |
| `org.springframework.jdbc.*`, `java.sql.*`, `javax.sql.*` | Persistence là chi tiết của adapter ra |
| Jackson | Serialization là chi tiết của adapter |
| `@Configuration`, `@Bean` | Đây là **lắp ráp hạ tầng**, không phải use case |

Dòng cuối là chỗ Task 4 đã lệch: một `@Configuration` tạo bean `Clock` nằm trong
`vehicle/application/service/`. Lắp ráp bean thuộc về `shared/` nếu dùng chung, hoặc
`<module>/config/` nếu thật sự riêng của một module.

Ranh giới này được khoá bằng **R11** trong `module-architecture.md` §8 — để nó là quy tắc máy kiểm
được, không phải câu chữ người ta đọc rồi quên.

## Hệ quả
- Nhiều lớp và interface hơn. Đổi lấy khả năng thay thế và test domain không cần CSDL.
- **Bắt buộc có test kiến trúc** chặn vi phạm, nếu không quy tắc sẽ mục dần khi có người vội.
- Domain test chạy không cần Spring context, phải nhanh.
