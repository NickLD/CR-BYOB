package com.crbyob.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The dashboard's scanner line and the USB 2 block on Start. */
public class ScannerLineTest {
    private static Dash.Snap probed(boolean usb3) {
        Dash.Snap s = new Dash.Snap();
        s.plugged = true;
        s.plugProbed = true;
        s.scannerModel = "CR-Scan Otter";
        s.scannerSn = "BCA000";
        s.scannerFw = "1.2.2";
        s.usbSuper = usb3;
        return s;
    }

    @Test
    public void bridgeOffShowsTheProbedScanner() {
        assertEquals("CR-Scan Otter · SN BCA000 · fw 1.2.2 · USB 3", MainActivity.scannerLine(probed(true), false));
    }

    @Test
    public void usb2BlocksStartOnlyWhenProbedAndOff() {
        assertTrue(MainActivity.usb2Block(probed(false)));
        assertFalse(MainActivity.usb2Block(probed(true)));
        Dash.Snap unprobed = probed(false);
        unprobed.plugProbed = false;                 // no USB access yet: speed unknown, don't block
        assertFalse(MainActivity.usb2Block(unprobed));
        Dash.Snap running = probed(false);
        running.scanner = true;                      // bridge already has it open: warn, don't block
        assertFalse(MainActivity.usb2Block(running));
        assertFalse(MainActivity.usb2Block(null));
        assertFalse(MainActivity.usb2Block(new Dash.Snap()));
    }

    @Test
    public void plainWordsWhenThereIsNoDetail() {
        assertEquals("No scanner plugged in · you can start now and plug it in later",
                MainActivity.scannerLine(new Dash.Snap(), false));
        Dash.Snap noAccess = probed(true);
        noAccess.plugProbed = false;
        assertEquals("CR-Scan Otter plugged in · Android asks for access at Start", MainActivity.scannerLine(noAccess, false));
        Dash.Snap run = new Dash.Snap();
        run.scannerState = "Not connected";
        assertEquals("No scanner plugged in", MainActivity.scannerLine(run, true));
        run.scannerState = "Attached, waiting for USB permission";
        assertTrue(MainActivity.scannerLine(run, true).contains("waiting for USB access"));
    }
}
