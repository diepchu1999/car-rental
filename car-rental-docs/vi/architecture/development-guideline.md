# Development Guideline

## 1. Tài liệu là nguồn sự thật
Khi một quyết định thay đổi:
1. Cập nhật `CLAUDE.md` / `AGENTS.md` nếu ảnh hưởng hành vi toàn cục.
2. Cập nhật doc kiến trúc hoặc nghiệp vụ liên quan.
3. Tạo hoặc cập nhật ADR nếu quyết định mang tính kiến trúc.
4. Ghi dev note khi kết thúc phiên làm việc.

Không để quyết định quan trọng chỉ nằm trong chat.

## 2. Quy tắc số một: không bịa quy tắc nghiệp vụ

149 quy tắc trong `../business/business-rules.md` được soạn qua 9 vòng phỏng vấn Tech Owner.
Trước khi hiện thực bất kỳ hành vi nghiệp vụ nào, **phải trích được mã BR**.

Không trích được thì **dừng lại và hỏi Tech Owner**. Đoán một con số "tạm để chạy được" là cách
nhanh nhất biến giả định thành sự thật vĩnh viễn: nó vào schema, vào test, vào dữ liệu, và sáu tháng
sau không ai nhớ nó từng là phỏng đoán.

Danh sách những gì **chưa** được chốt nằm ở cuối `business-rules.md` — đọc nó trước khi bắt đầu.

## 3. Trước khi code
1. Đọc `CLAUDE.md`.
2. Đọc phần "Quy tắc chưa chốt" — biết cái gì chưa có.
3. Đọc quy tắc BR liên quan tới việc đang làm.
4. Kiểm tra ADR liên quan đã ACCEPTED chưa.
5. Đọc code hiện có.
6. Đề xuất kế hoạch ngắn gọn; chờ duyệt nếu task rộng hoặc rủi ro.

## 4. Cách xây: lát cắt dọc (ADR-0012)
Làm trọn một luồng nghiệp vụ xuyên mọi tầng rồi mới sang luồng sau. Mỗi lát cắt kết thúc bằng thứ
**demo được**, không phải bằng một tầng đã xong.

## 5. Cấu trúc repo — chốt, không tự đổi

```text
car-rental/
├── CLAUDE.md · AGENTS.md · README.md     # contract và chỉ mục
├── .gitignore · .env.example
├── car-rental-docs/                      # tài liệu
├── infra/                                # hạ tầng local, KHÔNG lẫn vào code
│   ├── docker-compose.yml
│   ├── postgres/init/
│   ├── keycloak/realm-export.json
│   └── minio/
├── car-rental-api/
│   ├── pom.xml · mvnw
│   └── src/
│       ├── main/java/com/carrental/
│       │   ├── CarRentalApplication.java
│       │   ├── shared/                   # tiện ích dùng chung, có gói con tên rõ nghĩa
│       │   │   ├── api/                  # ApiResponse, PageResponse
│       │   │   ├── error/                # DomainException, ErrorCode
│       │   │   ├── sql/                  # SqlLoader
│       │   │   └── validation/           # Validations, Enums
│       │   └── <module>/                 # cấu trúc bên trong: module-architecture.md §2
│       ├── main/resources/
│       │   ├── application.yml
│       │   ├── db/migration/             # Flyway — CHỈ migration
│       │   └── sql/<module>/             # native SQL nghiệp vụ — tách khỏi migration
│       └── test/java/com/carrental/
│           ├── architecture/             # ArchitectureRulesTest
│           └── <module>/                 # gương với main
├── customer-web/ · admin-web/            # React + TypeScript
└── mobile/                               # React Native
```

### Bảy quy tắc giữ repo sạch

1. **Một thư mục một trách nhiệm.** `infra/` không chứa code ứng dụng; `car-rental-api/` không chứa
   file hạ tầng.
2. **Root chỉ chứa contract, README và cấu hình repo.** Không để file tạm, script rời, hay ghi chú
   cá nhân ở root.
3. **Không có package `util`, `common`, `helper`.** Đó là nơi code đi vào để chết — không ai biết
   trong đó có gì và không ai dám xoá. Dùng `shared/` với gói con có tên nói rõ chức năng.
4. **SQL không nằm trong Java.** Migration ở `db/migration/`, SQL nghiệp vụ ở `sql/<module>/`.
   Hai thứ khác nhau, không trộn.
5. **Test gương với main.** Cùng đường dẫn gói, để tìm test của một lớp không phải đi tìm.
6. **Không tạo thư mục trước khi cần.** Module chỉ tạo layer thực sự dùng (module-architecture §2).
   Thư mục rỗng là lời hứa chưa thực hiện.
7. **`.gitignore` đúng ngay từ commit đầu.** Không commit `target/`, `node_modules/`, `.env`,
   và `.DS_Store` — máy macOS rải file này khắp nơi, dọn sau tốn công hơn chặn trước.

### Đặt tên
- Tên module: một từ, viết thường, không viết tắt — `booking`, `availability`, `dispatch`.
- Tên file và lớp theo quy ước ở `module-architecture.md` §3.
- Không đặt tên theo tầng kỹ thuật (`BookingHelper`, `BookingManager`) — tên phải nói **việc gì**,
  không nói *loại lớp gì*.

## 6. Quy tắc backend
- Spring Boot · Modular Monolith · SOLID · Clean/Hexagonal.
- Native SQL, PostgreSQL. Không JPA repository cho truy vấn nghiệp vụ.
- Không quy tắc nghiệp vụ trong controller.
- Không trả row CSDL trực tiếp làm response.
- Không truy cập bảng của module khác.
- Không `if` trên `ownership_type` hay `rental_type` ngoài `*PolicyResolver`.
- Không ghi vào bảng khoá lịch từ module khác `availability`.
- Không đọc `config` khi quyết toán — đọc bản sao trong đơn.

## 7. Quy tắc frontend và mobile
Xem [`frontend-guideline.md`](frontend-guideline.md) và [`mobile-guideline.md`](mobile-guideline.md).
Kiểu API sinh từ OpenAPI, không viết tay.

## 8. Migration
Flyway. Thay đổi CSDL mới nghĩa là migration mới. **Không sửa migration đã chạy.**

## 9. Testing
- Unit test cho domain — không cần Spring context, phải nhanh.
- Integration test dùng **Testcontainers với PostgreSQL thật**. Không dùng H2.
- API test cho endpoint quan trọng.
- **Test đồng thời thật** cho đặt xe, thanh toán, phân công người, mã giảm giá —
  danh sách kịch bản ở [`backend-guideline.md`](backend-guideline.md) §6.
- `ArchitectureRulesTest` phải xanh (R1 → R10).

## 10. Git
- Commit doc cùng code khi hành vi thay đổi.
- Commit message có ý nghĩa.
- Không commit secret. Giữ `.env.example` cập nhật.

## 11. Định nghĩa "Hoàn thành"
- Quy tắc nghiệp vụ đã hiện thực **và trích được mã BR**.
- Validation đã có.
- Tranh chấp tài nguyên xử lý bằng ràng buộc CSDL, không bằng kiểm tra ứng dụng.
- Thao tác tiền có idempotency và audit.
- Test đã thêm, gồm test đồng thời nếu chạm tài nguyên tranh chấp.
- `ArchitectureRulesTest` xanh.
- Doc đã cập nhật nếu hành vi hoặc quyết định kỹ thuật thay đổi.
- Endpoint thêm/sửa/xoá đã cập nhật trong danh mục API.
