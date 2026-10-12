package com.carrental.search.domain;

/** Các cách xếp BR-126; hỗ trợ khoảng cách và giá, đánh giá chưa có dữ liệu. */
public enum SearchSort {
    /** Khoảng cách tăng dần, cùng khoảng cách thì mã xe tăng dần. */
    NEAREST,
    /** Tiền thuê tăng dần, bằng nhau theo khoảng cách rồi mã xe. */
    PRICE_ASC,
    /** Đánh giá giảm dần, chờ module review. */
    RATING_DESC
}
