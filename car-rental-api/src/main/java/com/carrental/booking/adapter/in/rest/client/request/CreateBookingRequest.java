package com.carrental.booking.adapter.in.rest.client.request;

/** BR-122: khách chỉ chọn báo giá; giá, chủ, cọc và hạn giữ chỗ do hệ thống quyết định. */
public record CreateBookingRequest(String quoteCode) { }
