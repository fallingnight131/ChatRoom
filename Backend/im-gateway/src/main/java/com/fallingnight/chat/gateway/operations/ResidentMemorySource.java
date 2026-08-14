package com.fallingnight.chat.gateway.operations;

/** Platform boundary for current-process resident/working-set bytes. */
@FunctionalInterface
public interface ResidentMemorySource {
    long readResidentBytes() throws Exception;
}
