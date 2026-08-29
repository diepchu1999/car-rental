# ADR-0011: Thanh toán là port, adapter giai đoạn này là xác nhận thủ công

- Trạng thái: ACCEPTED · 27/08/2026 · Tech Owner

## Bối cảnh
BR-213: giai đoạn hiện tại khách chuyển khoản, **nhân viên đối chiếu và xác nhận bằng tay**.
Về sau sẽ tích hợp ví điện tử, đọc biến động số dư, và VietQR — nhưng chỉ khi Tech Owner yêu cầu.

## Quyết định
Vùng `payment` định nghĩa port `PaymentGateway` ở tầng application. Adapter hiện tại là
`ManualConfirmationAdapter`. Khi tích hợp thật, cắm adapter mới **không sửa use case**.

## Điều quan trọng nhất: thủ công không có nghĩa là lỏng

Con người đang đóng vai cổng thanh toán. Mà cổng thanh toán nào cũng có thể gửi tín hiệu trùng —
con người còn dễ hơn: bấm hai lần, bấm khi mạng chậm rồi bấm lại, hai nhân viên cùng xử lý một đơn.

Nên mọi thao tác ghi tiền cần đúng những gì một callback tự động cần:

| Yêu cầu | Áp dụng cho |
|---|---|
| Khoá idempotency | Xác nhận cọc · xác nhận phần còn lại · hoàn tiền · chuyển cọc khi đổi xe |
| Ghi audit đầy đủ | Ai xác nhận, khi nào, số tiền, đơn nào |
| Chặn xác nhận lại | Khoản đã xác nhận không xác nhận được lần hai |
| Không tin số tiền client gửi | Server luôn tính lại (BR-216) |

Áp dụng cả cho xác nhận hai phía khi bàn giao (BR-204, BR-205) và xác nhận thay của
`BRANCH_MANAGER` (BR-217) — thao tác thay phải ghi audit riêng.

## Lý do
Những ràng buộc trên gần như không trang bị bổ sung được sau khi đã có dữ liệu thật và code đã giả
định "xác nhận chỉ xảy ra một lần". Chi phí làm đúng bây giờ nhỏ; chi phí sửa sau là đối soát tiền
bằng tay.

## Hệ quả
- Ba khoản tiền có vòng đời riêng (`deposit`, `balance`, `collateral`) — xem `status-flow.md` §3.
- Sổ chỉ ghi thêm: hoàn tiền là bản ghi mới, không sửa bản ghi cũ.
- **Thế chấp ghi sổ là khoản phải trả, không phải doanh thu** — nó là tài sản giữ hộ.
