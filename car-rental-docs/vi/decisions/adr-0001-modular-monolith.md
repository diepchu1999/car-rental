# ADR-0001: Modular Monolith

- Trạng thái: ACCEPTED · 27/08/2026 · Tech Owner

## Bối cảnh
Đội nhỏ, chưa có xe, chưa có khách, chỉ chạy local. Ranh giới nghiệp vụ vừa được định hình qua
149 quy tắc nhưng chưa qua thử thách thực tế.

## Quyết định
Một khối triển khai duy nhất, chia module theo vùng nghiệp vụ trong `domain-map.md`.
Không tạo microservice trừ khi Tech Owner yêu cầu tường minh.

## Lý do
Các vùng lõi ràng buộc transaction rất chặt với nhau. Một lần đặt xe chạm đồng thời `availability`,
`pricing`, `booking`, `payment` — và BR-104 đòi hỏi khoá lịch với tạo đơn nằm **trong cùng một
transaction**. Tách service ở giai đoạn này biến một bất biến đơn giản thành transaction phân tán,
tức là đổi bài toán dễ lấy bài toán khó.

Ranh giới cũng chưa ổn định: `settlement` chưa tồn tại ở GĐ1, vùng đối tác chưa có người dùng thật.
Cắt service theo ranh giới chưa kiểm chứng sẽ đóng băng những đường cắt sai.

## Hệ quả
- Một tiến trình, một CSDL, nhiều schema (ADR-0008).
- Kỷ luật ranh giới do con người và test kiến trúc giữ, vì trình biên dịch không chặn giúp.
- Ứng viên tách trước nếu cần: `notification`, `search`, `reporting`.
- `availability` gần như **không bao giờ** nên tách — sức mạnh của ADR-0005 đến từ việc nó nằm cùng
  transaction với việc tạo đơn.
