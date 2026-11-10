package com.carrental.rental.application.port.in;

import com.carrental.rental.application.view.RentalTermsDetail;
import com.carrental.rental.application.query.GetRentalTermsQuery;

/** Cổng đọc điều kiện thuê, không mở transaction hoặc truy cập dữ liệu lịch xe. */
public interface GetRentalTermsUseCase {
    /** Kiểm query theo các policy BR-109/113/116/119/121 rồi trả điều kiện chưa cộng đệm. */
    RentalTermsDetail get(GetRentalTermsQuery query);
}
