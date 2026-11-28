package ro.solarstudios.flowcity.bridge.gps;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Teste pentru regulile de acces ale GPS-ului (teleportare, vizibilitate, validari). */
class GpsPolicyTest {
    @Test
    void jucatorNormalNuSeTeleporteaza() {
        assertFalse(GpsPolicy.canTeleport(true, false, false));
    }

    @Test
    void permisiuneaFaraOpNuAjunge() {
        // un client modificat sau un grup cu permisiunea, dar fara OP
        assertFalse(GpsPolicy.canTeleport(true, false, true));
    }

    @Test
    void opFaraPermisiuneNuAjunge() {
        // permisiunea poate fi negata explicit (de ex. din LuckPerms) chiar si unui operator
        assertFalse(GpsPolicy.canTeleport(true, true, false));
    }

    @Test
    void opCuPermisiuneSePoateTeleporta() {
        assertTrue(GpsPolicy.canTeleport(true, true, true));
    }

    @Test
    void functiaOpritaInConfigBlocheazaTotul() {
        assertFalse(GpsPolicy.canTeleport(false, true, true));
    }

    @Test
    void coordonateInvalideSuntRespinse() {
        assertFalse(GpsPolicy.validCoord(Double.NaN));
        assertFalse(GpsPolicy.validCoord(Double.POSITIVE_INFINITY));
        assertFalse(GpsPolicy.validCoord(Double.NEGATIVE_INFINITY));
        assertFalse(GpsPolicy.validCoord(30000001d));
        assertFalse(GpsPolicy.validCoord(-3.0E9));
        assertTrue(GpsPolicy.validCoord(0d));
        assertTrue(GpsPolicy.validCoord(-1234.5));
        assertTrue(GpsPolicy.validCoord(30000000d));
    }

    @Test
    void inaltimeaYTrebuieSaFieInLimite() {
        assertTrue(GpsPolicy.validY(-64));
        assertTrue(GpsPolicy.validY(64));
        assertTrue(GpsPolicy.validY(320));
        assertFalse(GpsPolicy.validY(-65));
        assertFalse(GpsPolicy.validY(321));
        assertFalse(GpsPolicy.validY(Double.NaN));
    }

    @Test
    void locatiaCuPermisiuneVizibilaDoarCuPermisiune() {
        Set<String> has = new HashSet<>(Arrays.asList("flowcity.gps.vip"));
        assertTrue(GpsPolicy.canSee("", false, has::contains));
        assertTrue(GpsPolicy.canSee(null, false, has::contains));
        assertTrue(GpsPolicy.canSee("flowcity.gps.vip", false, has::contains));
        assertFalse(GpsPolicy.canSee("flowcity.gps.staff", false, has::contains));
        // adminii o vad mereu, ca sa o poata edita
        assertTrue(GpsPolicy.canSee("flowcity.gps.staff", true, p -> false));
    }

    @Test
    void numeDePermisiuneValidate() {
        assertTrue(GpsPolicy.validPermNode(""));
        assertTrue(GpsPolicy.validPermNode("flowcity.gps.vip"));
        assertFalse(GpsPolicy.validPermNode("are spatiu"));
        assertFalse(GpsPolicy.validPermNode("a;b"));
        assertFalse(GpsPolicy.validPermNode(null));
        StringBuilder lung = new StringBuilder();
        for (int i = 0; i < 70; i++) lung.append('a');
        assertFalse(GpsPolicy.validPermNode(lung.toString()));
    }
}
