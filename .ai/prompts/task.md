# Quy trình soạn prompt task cho codex

> Dùng chung cho Claude Code và Codex. Wrapper của từng công cụ chỉ trỏ tới file này.
>
> Vai: **Chief Architect** soạn prompt · **Tech Owner** tự gõ code `main` ·
> **codex** (Principal Engineer) hướng dẫn, và tự viết test.

---

## Bước 1 — BẮT BUỘC làm trước: đối chiếu business rules

Đọc `car-rental-docs/vi/business/business-rules.md` và xác định **từng hành vi nghiệp vụ** trong
yêu cầu trích được mã BR nào.

**Nếu mọi hành vi đều có BR** → sang bước 2.

**Nếu có hành vi chưa có BR** → **DỪNG. Không soạn prompt.** Thay vào đó:

- Liệt kê rõ: hành vi nào đã có BR, hành vi nào chưa.
- Với phần chưa có, nêu **những câu Tech Owner cần chốt**, kèm phương án đề xuất và **đánh dấu rõ
  là đề xuất chờ duyệt**.
- Chỉ ra những BR hiện có mà tính năng mới có thể **mâu thuẫn** — đây là phần dễ bỏ sót nhất.
- Nói rõ: chốt xong mới soạn được prompt.

**Không bao giờ tự đặt ra một con số hay một quy tắc "tạm để chạy được".** Con số phỏng đoán sẽ vào
schema, vào test, vào dữ liệu, và sáu tháng sau không ai nhớ nó từng là phỏng đoán.

## Bước 2 — soạn prompt theo khung chuẩn

Xuất ra **một khối markdown liền** để Tech Owner copy nguyên khối đưa cho codex.

### §1 Bối cảnh
Repo ở `/Users/diepchu/project/car-rental/`. Nêu trạng thái hiện tại của code, và **liệt kê cụ thể
những tài liệu codex phải đọc trước** — chỉ những cái liên quan tới task này, không liệt kê hết.

Nêu phân vai: Tech Owner tự gõ code và chạy lệnh · Chief Architect đã viết tài liệu, sẽ review sau ·
codex là Principal Engineer.

### §2 QUAN TRỌNG NHẤT — cách codex phải hướng dẫn
Chép nguyên văn, **không rút gọn**:

> **Chia việc:**
>
> | Phạm vi | Ai làm |
> |---|---|
> | `src/main/**` — gồm cả migration và file `.sql` | **Tech Owner tự gõ.** Bạn hướng dẫn, không tự sửa file |
> | `src/test/**` | **Bạn tự viết và tự chạy** |
> | Chạy `./mvnw clean verify` | **Bạn chạy**, rồi báo kết quả |
>
> Với phần `src/main/**`, Tech Owner tự gõ hoặc copy. Vì vậy:
>
> 1. **Hướng dẫn từng bước một, theo thứ tự chạy được.**
> 2. **Viết đầy đủ nội dung từng file.** Không cắt bớt bằng `...`, không viết "phần còn lại tương tự".
>    Sửa một phần file có sẵn thì chỉ rõ đoạn cũ và đoạn mới, đủ ngữ cảnh để không dán nhầm chỗ.
> 3. **Nói rõ file nào, đường dẫn nào**, tạo mới hay sửa.
> 4. **Sau mỗi bước, giải thích ngắn gọn vừa làm gì và vì sao** — Tech Owner đang học trong lúc xây.
> 5. **Kèm lệnh kiểm chứng sau mỗi phần**, nói rõ kết quả đúng trông như thế nào, và nếu sai thì
>    thường sai ở đâu.
> 6. **Viết bằng tiếng Việt.**
> 7. Không làm quá phạm vi. Thấy việc đáng làm nhưng ngoài phạm vi thì ghi chú ở cuối, đừng tự làm.

Task lớn thì thêm: chia phần rõ ràng, **dừng chờ Tech Owner xác nhận sau mỗi phần**.

**Vì Tech Owner không đọc file test**, sau khi viết test bạn phải **tóm tắt bằng tiếng Việt từng
test khẳng định điều gì** — một dòng mỗi test. Không dán code test ra, chỉ nói nó kiểm cái gì.
Đây là cách duy nhất Tech Owner biết được thứ gì đã được phủ và thứ gì chưa.

### §3 Bước kế hoạch — thêm khi task lớn hoặc tinh tế
Bắt codex **trình bày cách làm trước, chờ duyệt, rồi mới viết code**.

Bài học thật: ở Task 3 và Task 4, bước này khiến codex tìm ra **lỗi trong chính tài liệu kiến trúc**
trước khi một dòng code nào được viết. Nếu bỏ qua, những lỗi đó đã nằm trong code.

Nêu rõ cần trình bày gì: lựa chọn công cụ và lý do · cơ chế cho từng phần khó · những thứ tham chiếu
tới cái chưa tồn tại thì xử lý sao.

### §4 Việc cần làm
**Trích mã BR cho từng hành vi.** Không trích được thì quay lại bước 1.

### §5 Ràng buộc
- Cấu trúc thư mục theo `development-guideline.md` §5.
- Chuẩn code theo `module-architecture.md`.
- Không commit secret.
- **Không chạy lệnh git nào có ghi** (`add`, `commit`, `push`, `reset`, `checkout`...).
  Được phép **đề xuất** commit message.

### §6 Tiêu chí nghiệm thu
Phải là thứ Tech Owner **tự chạy lệnh kiểm được**, không phải "codex bảo xong".

Ba loại tiêu chí đã chứng minh giá trị:
- **Phép thử phá hoại** — ràng buộc CSDL thì phải *cố tình vi phạm* rồi xem có bị chặn không.
  Test "tạo thành công" không chứng minh gì về bất biến.
- **Phép thử đảo** — rule mới thì phải sửa fixture cho hết vi phạm và xem test có **đỏ** không.
  Không có bước này thì rất dễ có một rule rỗng vẫn xanh.
- **Trường hợp được phép** — rule nào có ngoại lệ thì phải có fixture chứng minh ngoại lệ hoạt động.
  Bài học Task 3: R6 có ngoại lệ `*PolicyResolver` nhưng không fixture nào chạm tới nó, nên nhánh
  đó chưa bao giờ được chạy.

### §7 Ngoài phạm vi
Liệt kê **cụ thể** những gì đừng làm, kèm lý do ngắn.

Đây là chỗ hay hỏng nhất: giao việc "dựng X" thì codex sẽ tiện tay dựng luôn Y và Z, và một task
phình thành ba.

### §8 Báo cáo cuối task
- Những lựa chọn kỹ thuật đã chọn và **vì sao**.
- Chỗ nào thấy tài liệu chưa rõ hoặc mâu thuẫn — **ghi lại, đừng tự quyết**.
- Đề xuất commit message (Tech Owner tự chạy lệnh).
