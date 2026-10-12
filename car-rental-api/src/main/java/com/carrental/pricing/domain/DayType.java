package com.carrental.pricing.domain;

/** Nhãn loại ngày theo mức tăng thực sự được áp dụng, BR-218. */
public enum DayType {
    /** Không thuộc cuối tuần hoặc lịch lễ. */
    WEEKDAY,
    /** Áp mức tăng cuối tuần, kể cả khi mức này cao hơn mức lễ. */
    WEEKEND,
    /** Áp mức tăng lễ; mức bằng cuối tuần cũng chọn nhãn này. */
    HOLIDAY
}
