package com.carrental.architecture.fixtures.r9.booking.application.service;

import com.carrental.architecture.fixtures.r9.config.application.port.out.ConfigurationPort;

final class R9BookingViolation {

    private final ConfigurationPort configurationPort;

    R9BookingViolation(ConfigurationPort configurationPort) {
        this.configurationPort = configurationPort;
    }

    int recalculateExistingBooking() {
        return configurationPort.currentLateFeeRate();
    }
}
