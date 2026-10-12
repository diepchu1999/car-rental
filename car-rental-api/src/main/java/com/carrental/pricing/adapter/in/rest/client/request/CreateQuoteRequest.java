package com.carrental.pricing.adapter.in.rest.client.request;

import com.carrental.shared.rental.DriveMode;
import com.carrental.shared.rental.PickupMethod;
import com.carrental.shared.rental.RentalType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

/** Đầu vào HTTP báo giá BR-122/125; các trường giá/hạn ngoài hợp đồng được bỏ qua. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateQuoteRequest(String vehicleCode, Instant startAt, Instant endAt,
        RentalType rentalType, DriveMode driveMode, PickupMethod pickupMethod) { }
