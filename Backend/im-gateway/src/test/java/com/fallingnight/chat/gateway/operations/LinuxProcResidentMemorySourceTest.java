package com.fallingnight.chat.gateway.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class LinuxProcResidentMemorySourceTest {
    @Test
    void parsesOneStrictPositiveVmRssValueInKibibytes() throws Exception {
        assertEquals(12_345L * 1024,
                LinuxProcResidentMemorySource.parse(
                        "Name:\tjava\nVmSize:\t999 kB\nVmRSS:\t   12345 kB\n"));
    }

    @Test
    void rejectsMissingDuplicateWrongUnitZeroAndOverflow() {
        for (String value : new String[] {
                "Name:\tjava\n",
                "VmRSS:\t1 kB\nVmRSS:\t2 kB\n",
                "VmRSS:\t1 MB\n",
                "VmRSS:\t0 kB\n",
                "VmRSS:\t999999999999999999999999999 kB\n",
        }) {
            assertThrows(IOException.class,
                    () -> LinuxProcResidentMemorySource.parse(value), value);
        }
    }
}
