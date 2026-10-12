package com.carrental.pricing.application.port.out;

import com.carrental.pricing.domain.Quote;

/** Ghi aggregate, không nhận read view (R4); application sở hữu transaction. */
public interface WriteQuotePort {
    /** Chèn cả header và dòng; false chỉ khi trùng mã, lỗi khác truyền nguyên trạng để rollback. */
    boolean insert(Quote quote);
}
