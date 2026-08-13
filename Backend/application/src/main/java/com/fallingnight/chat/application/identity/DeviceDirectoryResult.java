package com.fallingnight.chat.application.identity;

import java.util.HashSet;
import java.util.List;

public sealed interface DeviceDirectoryResult {
    record Available(List<ManagedDevice> devices) implements DeviceDirectoryResult {
        public Available {
            devices = List.copyOf(devices);
            if (devices.isEmpty() || devices.size() > 100
                    || devices.stream().filter(ManagedDevice::current).count() != 1
                    || new HashSet<>(devices.stream().map(
                            ManagedDevice::deviceId).toList()).size() != devices.size())
                throw new IllegalArgumentException("invalid managed device directory");
        }
    }
    enum Rejected implements DeviceDirectoryResult { INSTANCE }
}
