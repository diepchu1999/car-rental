package com.carrental.architecture.fixtures.r12allowed.vehicle.application.view;

/** Read model nội bộ không thuộc adapter REST nên không bị R12 cấm ID số. */
public record InternalResponse(long id) {
}
