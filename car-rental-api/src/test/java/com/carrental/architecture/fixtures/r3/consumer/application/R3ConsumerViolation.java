package com.carrental.architecture.fixtures.r3.consumer.application;

import com.carrental.architecture.fixtures.r3.provider.api.R3ProviderApiType;
import com.carrental.architecture.fixtures.r3.provider.domain.R3ProviderInternalType;
import com.carrental.architecture.fixtures.r3.shared.error.R3SharedError;

public final class R3ConsumerViolation {

    public void acceptProviderTypes(
            R3ProviderApiType allowedApiType,
            R3ProviderInternalType forbiddenInternalType,
            R3SharedError allowedSharedError
    ) {
    }
}
