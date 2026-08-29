package com.carrental.architecture.fixtures.r9.fleet.application.service;

import com.carrental.architecture.fixtures.r9.config.application.port.out.ConfigurationPort;

final class R9FleetAllowed {

    private final ConfigurationPort configurationPort;

    R9FleetAllowed(ConfigurationPort configurationPort) {
        this.configurationPort = configurationPort;
    }

    int maintenanceIntervalKilometres() {
        return configurationPort.maintenanceIntervalKilometres();
    }
}
