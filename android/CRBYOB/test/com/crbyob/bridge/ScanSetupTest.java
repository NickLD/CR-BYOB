package com.crbyob.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

public class ScanSetupTest {

    /** SupportedScanConfig as Creality Scan 1.4.3 sent it for a CR-Scan Otter (mirror session 2026-11-06). */
    static final String OTTER = "{\"blue_light\": {\"support\": false}, \"infrared\": {"
            + "\"face\": {\"fuse_modes\": {\"geo\": {\"accuracy\": [true, true], \"fov\": [true, false], \"support\": true}, \"marker\": {\"support\": false}, \"markerless\": {\"support\": false}, \"rgbd\": {\"accuracy\": [true, true], \"fov\": [true, false], \"support\": true}}, \"support\": true}, "
            + "\"human\": {\"fuse_modes\": {\"geo\": {\"accuracy\": [true, true], \"fov\": [true, false], \"support\": true}, \"marker\": {\"support\": false}, \"markerless\": {\"support\": false}, \"rgbd\": {\"accuracy\": [true, true], \"fov\": [true, false], \"support\": true}}, \"support\": true}, "
            + "\"obj_big\": {\"fuse_modes\": {\"geo\": {\"accuracy\": [true, true], \"fov\": [true, false], \"support\": true}, \"marker\": {\"accuracy\": [false, true], \"fov\": [true, false], \"support\": true}, \"markerless\": {\"support\": false}, \"rgbd\": {\"accuracy\": [true, true], \"fov\": [true, false], \"support\": true}}, \"support\": true}, "
            + "\"obj_medium\": {\"fuse_modes\": {\"geo\": {\"accuracy\": [true, true], \"fov\": [true, false], \"support\": true}, \"marker\": {\"accuracy\": [false, true], \"fov\": [true, false], \"support\": true}, \"markerless\": {\"support\": false}, \"rgbd\": {\"accuracy\": [true, true], \"fov\": [true, false], \"support\": true}}, \"support\": true}, "
            + "\"obj_small\": {\"fuse_modes\": {\"geo\": {\"accuracy\": [true, true], \"fov\": [true, false], \"support\": true}, \"marker\": {\"accuracy\": [false, true], \"fov\": [true, false], \"support\": true}, \"markerless\": {\"support\": false}, \"rgbd\": {\"accuracy\": [true, true], \"fov\": [true, false], \"support\": true}}, \"support\": true}, "
            + "\"ref_rod\": false, \"remove_plane\": true, \"remove_turntable\": true, \"scan_type\": [true, false, true], \"support\": true, \"texture\": [true, false]}, \"pid\": 1754}";
    /** AppliedScanParams from the same session: big object, high accuracy, texture (RGBD). */
    static final String APPLIED = "{\"1\":0,\"2\":0,\"3\":0,\"4\":3,\"5\":1,\"6\":1,\"7\":0,\"8\":0,\"9\":0,\"10\":0,\"11\":0,\"12\":2}";

    @Test public void otterOptionsAndDefaults() throws Exception {
        ScanSetup s = new ScanSetup(OTTER, new JSONObject(APPLIED));
        assertEquals(List.of(0), s.projections());                 // infrared only
        assertEquals(List.of(0, 1, 2, 3, 4), s.modes());           // face, small, medium, big, human
        assertEquals(3, s.mode);                                    // big, from AppliedScanParams
        assertEquals(List.of(0, 1, 2), s.fuses());                  // geometry, texture, marker for objects
        assertEquals(1, s.fuse);
        assertEquals(List.of(0, 1), s.accuracies());
        assertEquals(List.of(0), s.fovs());                         // normal only: no choice to show
        assertTrue(s.valid());
    }

    @Test public void faceHasNoMarkersAndMarkersAreHighAccuracyOnly() throws Exception {
        ScanSetup s = new ScanSetup(OTTER, new JSONObject(APPLIED));
        s.fuse = 2;                                                 // marker
        s.accuracy = 0;                                             // fast: not allowed with markers
        s.fix();
        assertEquals(List.of(1), s.accuracies());
        assertEquals(1, s.accuracy);
        s.mode = 0;                                                 // face: no marker mode
        s.fix();
        assertEquals(List.of(0, 1), s.fuses());
        assertEquals(1, s.fuse);                                    // falls to the last allowed
        assertTrue(s.valid());
    }

    @Test public void paramsCarryOnlyChosenSettings() throws Exception {
        ScanSetup s = new ScanSetup(OTTER, new JSONObject(APPLIED));
        s.mode = 1;
        s.fuse = 0;
        s.accuracy = 0;
        s.fix();
        JSONObject d = s.params().getJSONObject("DicDatas");
        assertEquals(0, d.getInt("3"));
        assertEquals(1, d.getInt("4"));
        assertEquals(0, d.getInt("6"));
        assertEquals(0, d.getInt("5"));
        assertEquals(0, d.getInt("7"));
        assertEquals(0, d.getInt("1"));
    }

    @Test public void objectColorOnlyInMarkerMode() throws Exception {
        ScanSetup s = new ScanSetup(OTTER, new JSONObject(APPLIED));   // texture alignment
        assertTrue(s.materials().isEmpty());
        assertTrue(!s.params().getJSONObject("DicDatas").has("12"));
        s.fuse = ScanSetup.FUSE_MARKER;
        s.material = 1;                                                 // black
        s.fix();
        assertEquals(List.of(0, 1, 2, 3), s.materials());
        assertEquals(1, s.params().getJSONObject("DicDatas").getInt("12"));
    }

    @Test public void worksWithoutAppliedParams() throws Exception {
        ScanSetup s = new ScanSetup(new JSONObject(OTTER), null);
        assertTrue(s.valid());
    }
}
