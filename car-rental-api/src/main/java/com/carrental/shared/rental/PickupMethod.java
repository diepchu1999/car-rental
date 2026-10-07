package com.carrental.shared.rental;

/** Cách nhận xe theo BR-121 và BR-126; quy tắc thời gian thuộc booking. */
public enum PickupMethod {
    /** Khách nhận xe tại chi nhánh. */
    BRANCH,
    /** Nhân viên giao xe tận nơi. */
    DELIVERY
}
