package ro.solarstudios.flowcity.maps;

import net.minecraft.client.gui.GuiGraphics;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import ro.solarstudios.flowcity.client.ui.Ui;

/** Iconitele hartii FlowCity (desenate de noi): foaia blips.png, 8 coloane x 4 randuri, celule de 32 px. */
public final class Blips {
    private Blips() {}

    private static final ResourceLocation SHEET = new ResourceLocation("flowcitycore", "textures/gui/blips.png");

    /** {cheie, eticheta, index icon} */
    public static final String[][] TYPES = {
        {"banca", "Banca", "0"}, {"atm", "ATM", "0"}, {"garaj", "Garaj", "1"}, {"casa", "Locuinta", "2"},
        {"benzinarie", "Benzinarie", "3"}, {"restaurant", "Restaurant", "4"}, {"primarie", "Primarie", "5"},
        {"spital", "Spital", "6"}, {"politie", "Politie", "7"}, {"dealer", "Dealer auto", "9"},
        {"magazin", "Magazin", "10"}, {"supermarket", "Supermarket", "11"}, {"mecanic", "Service auto", "12"},
        {"job", "Joburi", "13"}, {"special", "Special", "14"}, {"biserica", "Biserica", "15"},
        {"autobuz", "Autobuz", "17"}, {"armurier", "Armurier", "18"}, {"casino", "Casino", "19"},
        {"camion", "Transport", "20"}, {"aeroport", "Aeroport", "21"}, {"pescuit", "Pescuit", "22"},
        {"colete", "Colete", "23"}, {"electric", "Electrician", "24"}, {"fermier", "Fermier", "25"},
        {"gunoi", "Gunoi", "26"}, {"lemnar", "Lemnar", "27"}, {"pizza", "Pizza", "28"}, {"santier", "Santier", "29"},
        {"steag", "Steag", "8"}, {"inima", "Favorit", "30"}, {"tabara", "Tabara", "31"}, {"moarte", "Moarte", "16"},
        {"altele", "Altele", "14"},
    };

    private static String[] find(String type) {
        for (String[] t : TYPES) if (t[0].equals(type)) return t;
        return TYPES[TYPES.length - 1];
    }

    public static int icon(String type) {
        return Integer.parseInt(find(type)[2]);
    }

    public static String label(String type) {
        return find(type)[1];
    }

    /** Deseneaza iconita centrata, cu inel (ca in GTA). */
    public static void draw(GuiGraphics ms, String type, int cx, int cy, int size, boolean highlight) {
        int r = size / 2;
        if (highlight) Ui.round(ms, cx - r - 2, cy - r - 2, size + 4, size + 4, 0xFFE9A23B);
        Ui.round(ms, cx - r, cy + r - 1, size, 2, 0x55000000); // umbra
        sprite(ms, icon(type), cx - r, cy - r, size);
    }

    public static void sprite(GuiGraphics ms, int idx, int x, int y, int size) {
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        Minecraft.getInstance().getTextureManager().getTexture(SHEET).bind();
        RenderSystem.texParameter(3553, 10241, 9729);
        RenderSystem.texParameter(3553, 10240, 9729);
        ms.blit(SHEET, x, y, size, size, (idx % 8) * 32f, (idx / 8) * 32f, 32, 32, 256, 128);
    }

    /** Pin pentru waypoint (magenta, cu varf). */
    public static void pin(GuiGraphics ms, int cx, int cy, int size, int color) {
        int r = size / 2;
        Ui.round(ms, cx - r - 1, cy - size - 1, size + 2, size + 2, 0xFFFFFFFF);
        Ui.round(ms, cx - r, cy - size, size, size, color);
        for (int i = 0; i < 4; i++) Ui.rect(ms, cx - 3 + i, cy - 2 + i, 7 - i * 2, 1, i == 3 ? 0xFFFFFFFF : color);
        Ui.rect(ms, cx - 1, cy - size / 2 - 2, 3, 3, 0xFFFFFFFF);
    }
}
