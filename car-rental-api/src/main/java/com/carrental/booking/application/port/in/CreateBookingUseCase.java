package com.carrental.booking.application.port.in;

import com.carrental.booking.application.command.CreateBookingCommand;
import com.carrental.booking.application.view.BookingDetail;

/** Tạo đơn của khách hiện tại, giữ chỗ và ghi đơn cùng transaction (BR-102/122/131). */
public interface CreateBookingUseCase {
    /** Key còn hạn cùng nội dung trả kết quả tạo ban đầu; không giữ xe hoặc kiểm lại báo giá lần hai. */
    BookingDetail create(CreateBookingCommand command);
}
