package com.carrental.pricing.domain;

/** Các dòng tiền thuê theo glossary và BR-114/235/236; bảo hiểm là khoản riêng. */
public enum QuoteLineType {
    /** Một khối 24 giờ hoặc mức thuê tối thiểu một ngày. */
    DAY_BLOCK,
    /** Giờ lẻ gộp sau khối ngày cuối, không quá bốn giờ. */
    REMAINDER_HOURS,
    /** Gói bốn giờ đầu. */
    HOURLY_PACKAGE,
    /** Giờ vượt gộp sau gói bốn giờ. */
    OVERAGE_HOURS
}
