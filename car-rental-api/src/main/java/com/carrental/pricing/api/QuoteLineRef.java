package com.carrental.pricing.api;

import java.math.BigDecimal;
import java.time.Instant;

/** Dòng tiền thuê đã chốt, dùng để sao chép sang đơn; tên loại giữ nguyên hợp đồng pricing (ADR-0014). */
public record QuoteLineRef(String type, String dayType, Instant startAt, int hours,
        long dailyRate, BigDecimal uplift, long amount) { }
