package ro.solarstudios.flowcity.vehicle;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.resources.ResourceLocation;
import ro.solarstudios.flowcity.FlowCity;

/**
 * Modelele FlowCity (cutii), construite din dimensiunile din {@link VehicleTypes#SPECS}: sedan, SUV, duba, compacta, sport.
 * Fata e spre -Z, solul la y = 24 (pixeli de model). Farurile, stopurile si girofarul se deseneaza cu lumina maxima
 * cand sunt aprinse.
 */
public final class CarModel {
    private static final String[] BODY_NAMES = {"sedan", "suv", "van", "hatch", "sport"};
    public static final ModelLayerLocation[] LAYERS = new ModelLayerLocation[VehicleTypes.BODIES];
    public static final ResourceLocation TEXTURE = new ResourceLocation(FlowCity.MODID, "textures/entity/car.png");

    static {
        for (int i = 0; i < LAYERS.length; i++) LAYERS[i] = new ModelLayerLocation(new ResourceLocation(FlowCity.MODID, "car_" + BODY_NAMES[i]), "main");
    }

    private final ModelPart body;
    private final ModelPart trim;
    private final ModelPart glass;
    private final ModelPart front;
    private final ModelPart rear;
    private final ModelPart barA;
    private final ModelPart barB;
    private final ModelPart barBase;
    private final ModelPart sign;
    private final ModelPart stripe;
    private final ModelPart[] wheels = new ModelPart[4];

    public CarModel(ModelPart root) {
        body = root.getChild("body");
        trim = root.getChild("trim");
        glass = root.getChild("glass");
        front = root.getChild("front");
        rear = root.getChild("rear");
        barA = root.getChild("bar_a");
        barB = root.getChild("bar_b");
        barBase = root.getChild("bar_base");
        sign = root.getChild("sign");
        stripe = root.getChild("stripe");
        for (int i = 0; i < 4; i++) wheels[i] = root.getChild("wheel" + i);
    }

    /** Box cu coordonate de la colt la colt (mai usor de citit decat origine + marime). */
    private static CubeListBuilder box(CubeListBuilder b, float x0, float y0, float z0, float x1, float y1, float z1) {
        return b.addBox(x0, y0, z0, x1 - x0, y1 - y0, z1 - z0);
    }

    public static LayerDefinition createLayer(int bodyType) {
        VehicleTypes.Spec s = VehicleTypes.SPECS[bodyType];
        boolean cargo = s.cargoFrom < s.l;
        float w = s.w, l = s.l;
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        PartPose o = PartPose.ZERO;

        // caroseria: partea de jos pe toata lungimea, cabina (stalpi + plafon), cala la duba
        CubeListBuilder b = CubeListBuilder.create().texOffs(0, 0);
        box(b, -w, s.belt, -l, w, s.sill, l);
        float roofEnd = cargo ? l - 0.5f : s.cabB - 1;
        box(b, -w + 1.5f, s.roof, s.cabF + 1.5f, w - 1.5f, s.roof + 1.5f, roofEnd);
        for (float x : new float[] {-w + 1, w - 2}) {
            box(b, x, s.roof + 1.5f, s.cabF, x + 1, s.belt, s.cabF + 1.5f);                 // stalp A
            if (!cargo) box(b, x, s.roof + 1.5f, s.cabB - 2.5f, x + 1, s.belt, s.cabB - 1);  // stalp C
        }
        if (cargo) box(b, -w + 0.5f, s.roof, s.cargoFrom, w - 0.5f, s.belt, l - 0.5f);     // cala dubei
        if (bodyType == VehicleTypes.SPORT) {
            box(b, -w + 2, s.belt - 3, l - 5, w - 2, s.belt - 2, l - 1);                   // eleron
            box(b, -w + 4, s.belt - 2, l - 4, -w + 5, s.belt, l - 2);
            box(b, w - 5, s.belt - 2, l - 4, w - 4, s.belt, l - 2);
        }
        if (bodyType == VehicleTypes.SUV) box(b, -w + 2, s.roof - 1, s.cabF + 4, w - 2, s.roof, s.cabF + 5);   // bare de plafon
        root.addOrReplaceChild("body", b, o);

        CubeListBuilder t = CubeListBuilder.create().texOffs(0, 0);
        box(t, -w - 0.5f, s.sill - 3, -l - 0.8f, w + 0.5f, s.sill - 1, -l + 0.5f);   // bara fata
        box(t, -w - 0.5f, s.sill - 3, l - 0.5f, w + 0.5f, s.sill - 1, l + 0.8f);     // bara spate
        box(t, -w - 0.3f, s.sill - 1.5f, -l + 8, -w + 0.2f, s.sill - 0.5f, l - 8);   // praguri
        box(t, w - 0.2f, s.sill - 1.5f, -l + 8, w + 0.3f, s.sill - 0.5f, l - 8);
        box(t, -w + 4, s.belt + 0.5f, -l - 0.4f, w - 4, s.belt + 3, -l);             // grila
        root.addOrReplaceChild("trim", t, o);

        CubeListBuilder g = CubeListBuilder.create().texOffs(0, 0);
        box(g, -w + 2, s.roof + 1.5f, s.cabF, w - 2, s.belt, s.cabF + 1);              // parbriz
        if (!cargo) box(g, -w + 2, s.roof + 1.5f, s.cabB - 2, w - 2, s.belt, s.cabB - 1); // luneta
        float sideEnd = cargo ? s.cargoFrom : s.cabB - 2.5f;
        box(g, -w + 0.7f, s.roof + 1.5f, s.cabF + 1.5f, -w + 1.3f, s.belt, sideEnd);    // geamuri laterale
        box(g, w - 1.3f, s.roof + 1.5f, s.cabF + 1.5f, w - 0.7f, s.belt, sideEnd);
        root.addOrReplaceChild("glass", g, o);

        float ly = s.belt + 1.5f;
        root.addOrReplaceChild("front", box(box(CubeListBuilder.create().texOffs(0, 0),
            -w + 1, ly, -l - 0.6f, -w + 6, ly + 2, -l), w - 6, ly, -l - 0.6f, w - 1, ly + 2, -l), o);
        root.addOrReplaceChild("rear", box(box(CubeListBuilder.create().texOffs(0, 0),
            -w + 1, ly, l, -w + 5, ly + 2, l + 0.6f), w - 5, ly, l, w - 1, ly + 2, l + 0.6f), o);

        float sy = (s.belt + s.sill) / 2f - 1;
        root.addOrReplaceChild("stripe", box(box(CubeListBuilder.create().texOffs(0, 0),
            -w - 0.3f, sy, -l + 1, -w + 0.1f, sy + 2, l - 1), w - 0.1f, sy, -l + 1, w + 0.3f, sy + 2, l - 1), o);

        // girofar: deasupra cabinei
        float bz = cargo ? s.cabF + 6 : (s.cabF + s.cabB) / 2f - 2;
        root.addOrReplaceChild("bar_base", box(CubeListBuilder.create().texOffs(0, 0), -9, s.roof - 1, bz - 2, 9, s.roof, bz + 2), o);
        root.addOrReplaceChild("bar_a", box(CubeListBuilder.create().texOffs(0, 0), -8.5f, s.roof - 3, bz - 1.5f, -0.5f, s.roof - 1, bz + 1.5f), o);
        root.addOrReplaceChild("bar_b", box(CubeListBuilder.create().texOffs(0, 0), 0.5f, s.roof - 3, bz - 1.5f, 8.5f, s.roof - 1, bz + 1.5f), o);
        root.addOrReplaceChild("sign", box(CubeListBuilder.create().texOffs(0, 0), -5, s.roof - 3, bz - 1.5f, 5, s.roof, bz + 1.5f), o);

        float r = s.wheelR;
        float[][] pos = {{-w + 0.5f, -s.wheelZ}, {w - 0.5f, -s.wheelZ}, {-w + 0.5f, s.wheelZ}, {w - 0.5f, s.wheelZ}};
        for (int i = 0; i < 4; i++) {
            root.addOrReplaceChild("wheel" + i, CubeListBuilder.create().texOffs(0, 0).addBox(-2.5f, -r, -r, 5f, r * 2, r * 2),
                PartPose.offset(pos[i][0], 24f - r, pos[i][1]));
        }
        return LayerDefinition.create(mesh, 128, 128);
    }

    private static void part(ModelPart p, PoseStack pose, VertexConsumer buf, int light, int overlay, int rgb) {
        p.render(pose, buf, light, overlay, ((rgb >> 16) & 255) / 255f, ((rgb >> 8) & 255) / 255f, (rgb & 255) / 255f, 1f);
    }

    /**
     * @param lights faruri aprinse; @param brake stopuri; @param beacon girofar (0 = stins, 1 = culoarea A, 2 = culoarea B);
     * @param flash clipit la incuiere
     */
    public void render(PoseStack pose, VertexConsumer buf, int light, int overlay, int rgb, VehicleTypes.Type type, float wheelRot,
                       boolean lights, boolean brake, int beacon, boolean flash) {
        int full = LightTexture.FULL_BRIGHT;
        VehicleTypes.Livery lv = type.livery;
        part(body, pose, buf, light, overlay, lv.body < 0 ? rgb : lv.body);
        part(trim, pose, buf, light, overlay, 0x22252A);
        part(glass, pose, buf, light, overlay, 0x2B3640);
        if (lv.stripe >= 0) part(stripe, pose, buf, light, overlay, lv.stripe);
        boolean f = lights || flash;
        part(front, pose, buf, f ? full : light, overlay, f ? 0xFFF6DC : 0xB8B4A6);
        boolean r = lights || brake || flash;
        part(rear, pose, buf, r ? full : light, overlay, brake ? 0xFF2A1E : (r ? 0xC4281F : 0x6E1A16));
        if (lv.beaconA != 0) {
            part(barBase, pose, buf, light, overlay, 0x1A1C20);
            part(barA, pose, buf, beacon == 1 ? full : light, overlay, beacon == 1 ? lv.beaconA : dim(lv.beaconA));
            part(barB, pose, buf, beacon == 2 ? full : light, overlay, beacon == 2 ? lv.beaconB : dim(lv.beaconB));
        }
        if (lv.sign) part(sign, pose, buf, lights ? full : light, overlay, lights ? 0xFFF3C8 : 0xE9E4D2);
        for (ModelPart w : wheels) {
            w.xRot = wheelRot;
            part(w, pose, buf, light, overlay, 0x16171A);
        }
    }

    private static int dim(int rgb) {
        return ((((rgb >> 16) & 255) * 2 / 5) << 16) | ((((rgb >> 8) & 255) * 2 / 5) << 8) | ((rgb & 255) * 2 / 5);
    }
}
