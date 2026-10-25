package io.github.felixlp.libkat;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class UpdaterTest {
    @Test
    public void versionsCompareNumerically() {
        assertTrue(Updater.compare("0.4.0", "0.3.0") > 0);
        assertTrue(Updater.compare("0.10.0", "0.9.9") > 0);
        assertTrue(Updater.compare("1.0", "0.99.99") > 0);
        assertEquals(0, Updater.compare("0.4.0", "0.4"));
        assertTrue(Updater.compare("0.4.0", "0.4.1") < 0);
    }
}
