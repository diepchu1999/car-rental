package com.carrental.search.domain;

/** Các cách xếp theo BR-126; lát cắt 1 chỉ hỗ trợ NEAREST, hai cách còn lại phải báo chưa hỗ trợ. */
public enum SearchSort {
    /** Khoảng cách tăng dần, cùng khoảng cách thì ID xe tăng dần. */
    NEAREST,
    /** Giá tăng dần, chờ module pricing. */
    PRICE_ASC,
    /** Đánh giá giảm dần, chờ module review. */
    RATING_DESC
}
