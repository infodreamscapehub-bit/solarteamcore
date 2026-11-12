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
 * Model simplu al masinii (cutii), desenat de noi. Fata e spre -Z. Farurile, stopurile si girofarul se deseneaza
 * cu lumina maxima cand sunt aprinse (straluceste si noaptea). Se poate inlocui cu un model din Blockbench.
 */
public final class CarModel {
    public static final ModelLayerLocation LAYER = new ModelLayerLocation(new ResourceLocation(FlowCity.MODID, "car"), "main");
    public static final ResourceLocation TEXTURE = new ResourceLocation(FlowCity.MODID, "textures/entity/car.png");

    private final ModelPart body;
    private final ModelPart trim;
    private final ModelPart glass;
    private final ModelPart front;
    private final ModelPart rear;
    private final ModelPart barRed;
    private final ModelPart barBlue;
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
        barRed = root.getChild("bar_red");
        barBlue = root.getChild("bar_blue");
        barBase = root.getChild("bar_base");
        sign = root.getChild("sign");
        stripe = root.getChild("stripe");
        for (int i = 0; i < 4; i++) wheels[i] = root.getChild("wheel" + i);
    }

    public static LayerDefinition createLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        PartPose base = PartPose.offset(0f, 22f, 0f);
        // caroserie: sasiu jos + capota + cabina + portbagaj (sol = y 24)
        root.addOrReplaceChild("body", CubeListBuilder.create().texOffs(0, 0)
            .addBox(-14f, -9f, -32f, 28f, 7f, 64f)       // sasiu
            .addBox(-13f, -11f, -31f, 26f, 2f, 18f)      // capota
            .addBox(-13f, -11f, 15f, 26f, 2f, 16f)       // portbagaj
            .addBox(-12f, -18f, -9f, 24f, 2f, 22f)       // plafon
            .addBox(-12.5f, -16f, -10f, 1f, 5f, 24f)     // stalpi stanga
            .addBox(11.5f, -16f, -10f, 1f, 5f, 24f), base);
        root.addOrReplaceChild("trim", CubeListBuilder.create().texOffs(0, 0)
            .addBox(-14.5f, -4f, -32.5f, 29f, 2f, 1f)    // bara fata
            .addBox(-14.5f, -4f, 31.5f, 29f, 2f, 1f)     // bara spate
            .addBox(-14.3f, -6f, -12f, 0.6f, 1f, 24f)    // praguri
            .addBox(13.7f, -6f, -12f, 0.6f, 1f, 24f), base);
        root.addOrReplaceChild("glass", CubeListBuilder.create().texOffs(0, 0)
            .addBox(-11.5f, -16f, -13f, 23f, 5f, 3f)     // parbriz
            .addBox(-11.5f, -16f, 13f, 23f, 5f, 2f)      // luneta
            .addBox(-12.2f, -16f, -9f, 0.6f, 5f, 21f)    // geam stanga
            .addBox(11.6f, -16f, -9f, 0.6f, 5f, 21f), base);
        root.addOrReplaceChild("front", CubeListBuilder.create().texOffs(0, 0)
            .addBox(-12f, -8f, -32.6f, 6f, 2f, 1f).addBox(6f, -8f, -32.6f, 6f, 2f, 1f), base);
        root.addOrReplaceChild("rear", CubeListBuilder.create().texOffs(0, 0)
            .addBox(-13f, -8f, 31.6f, 5f, 2f, 1f).addBox(8f, -8f, 31.6f, 5f, 2f, 1f), base);
        // girofar pe plafon (politie)
        root.addOrReplaceChild("bar_base", CubeListBuilder.create().texOffs(0, 0).addBox(-9f, -19f, -1f, 18f, 1f, 4f), base);
        root.addOrReplaceChild("bar_red", CubeListBuilder.create().texOffs(0, 0).addBox(-8.5f, -21f, -0.5f, 8f, 2f, 3f), base);
        root.addOrReplaceChild("bar_blue", CubeListBuilder.create().texOffs(0, 0).addBox(0.5f, -21f, -0.5f, 8f, 2f, 3f), base);
        // caseta taxi
        root.addOrReplaceChild("sign", CubeListBuilder.create().texOffs(0, 0).addBox(-5f, -21f, 0f, 10f, 3f, 3f), base);
        // banda laterala (politie)
        root.addOrReplaceChild("stripe", CubeListBuilder.create().texOffs(0, 0)
            .addBox(-14.2f, -8f, -30f, 0.5f, 2f, 60f).addBox(13.7f, -8f, -30f, 0.5f, 2f, 60f), base);
        float[][] pos = {{-14.5f, 20f, -20f}, {14.5f, 20f, -20f}, {-14.5f, 20f, 20f}, {14.5f, 20f, 20f}};
        for (int i = 0; i < 4; i++) {
            root.addOrReplaceChild("wheel" + i, CubeListBuilder.create().texOffs(0, 0).addBox(-2.5f, -4.5f, -4.5f, 5f, 9f, 9f),
                PartPose.offset(pos[i][0], pos[i][1], pos[i][2]));
        }
        return LayerDefinition.create(mesh, 128, 128);
    }

    private static void part(ModelPart p, PoseStack pose, VertexConsumer buf, int light, int overlay, int rgb) {
        p.render(pose, buf, light, overlay, ((rgb >> 16) & 255) / 255f, ((rgb >> 8) & 255) / 255f, (rgb & 255) / 255f, 1f);
    }

    /**
     * @param lights faruri aprinse; @param brake stopuri; @param beacon girofar (0 = stins, 1 = rosu, 2 = albastru); @param flash clipit la incuiere
     */
    public void render(PoseStack pose, VertexConsumer buf, int light, int overlay, int rgb, int variant, float wheelRot,
                       boolean lights, boolean brake, int beacon, boolean flash) {
        int full = LightTexture.FULL_BRIGHT;
        part(body, pose, buf, light, overlay, rgb);
        part(trim, pose, buf, light, overlay, 0x22252A);
        part(glass, pose, buf, light, overlay, 0x2B3640);
        boolean f = lights || flash;
        part(front, pose, buf, f ? full : light, overlay, f ? 0xFFF6DC : 0xB8B4A6);
        boolean r = lights || brake || flash;
        part(rear, pose, buf, r ? full : light, overlay, brake ? 0xFF2A1E : (r ? 0xC4281F : 0x6E1A16));
        if (variant == CarEntity.V_POLICE) {
            part(stripe, pose, buf, light, overlay, 0x1F4E9C);
            part(barBase, pose, buf, light, overlay, 0x1A1C20);
            part(barRed, pose, buf, beacon == 1 ? full : light, overlay, beacon == 1 ? 0xFF3B30 : 0x6B1E1A);
            part(barBlue, pose, buf, beacon == 2 ? full : light, overlay, beacon == 2 ? 0x3B7BFF : 0x1B2B66);
        } else if (variant == CarEntity.V_TAXI) {
            part(sign, pose, buf, lights ? full : light, overlay, lights ? 0xFFF3C8 : 0xE9E4D2);
        }
        for (ModelPart w : wheels) {
            w.xRot = wheelRot;
            part(w, pose, buf, light, overlay, 0x16171A);
        }
    }
}
