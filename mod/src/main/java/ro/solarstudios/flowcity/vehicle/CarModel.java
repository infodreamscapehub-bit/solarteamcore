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
import net.minecraft.resources.ResourceLocation;
import ro.solarstudios.flowcity.FlowCity;

/**
 * Model provizoriu al masinii (cutii). Cand ai modelul din Blockbench (export "Java Entity", Mojang mappings),
 * se inlocuieste aceasta clasa; sistemul de conducere nu se schimba. Fata masinii e spre -Z in spatiul modelului.
 */
public final class CarModel {
    public static final ModelLayerLocation LAYER = new ModelLayerLocation(new ResourceLocation(FlowCity.MODID, "car"), "main");
    public static final ResourceLocation TEXTURE = new ResourceLocation(FlowCity.MODID, "textures/entity/car.png");

    private final ModelPart body;
    private final ModelPart glass;
    private final ModelPart lights;
    private final ModelPart[] wheels = new ModelPart[4];

    public CarModel(ModelPart root) {
        body = root.getChild("body");
        glass = root.getChild("glass");
        lights = root.getChild("lights");
        for (int i = 0; i < 4; i++) wheels[i] = root.getChild("wheel" + i);
    }

    public static LayerDefinition createLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        // sol = y 24; latime 28, lungime 64
        root.addOrReplaceChild("body", CubeListBuilder.create().texOffs(0, 0)
            .addBox(-14f, -10f, -32f, 28f, 8f, 64f)
            .texOffs(0, 0).addBox(-12f, -16f, -6f, 24f, 6f, 28f), PartPose.offset(0f, 22f, 0f));
        root.addOrReplaceChild("glass", CubeListBuilder.create().texOffs(0, 0)
            .addBox(-12.5f, -15f, -5f, 25f, 4f, 26f), PartPose.offset(0f, 22f, 0f));
        root.addOrReplaceChild("lights", CubeListBuilder.create().texOffs(0, 0)
            .addBox(-12f, -8f, -32.5f, 5f, 3f, 1f).addBox(7f, -8f, -32.5f, 5f, 3f, 1f)
            .addBox(-12f, -8f, 31.5f, 5f, 3f, 1f).addBox(7f, -8f, 31.5f, 5f, 3f, 1f), PartPose.offset(0f, 22f, 0f));
        float[][] pos = {{-15f, 20f, -20f}, {15f, 20f, -20f}, {-15f, 20f, 20f}, {15f, 20f, 20f}};
        for (int i = 0; i < 4; i++) {
            root.addOrReplaceChild("wheel" + i, CubeListBuilder.create().texOffs(0, 0).addBox(-2.5f, -5f, -5f, 5f, 10f, 10f),
                PartPose.offset(pos[i][0], pos[i][1], pos[i][2]));
        }
        return LayerDefinition.create(mesh, 128, 128);
    }

    public void render(PoseStack pose, VertexConsumer buf, int light, int overlay, int rgb, float wheelRot) {
        float r = ((rgb >> 16) & 255) / 255f;
        float g = ((rgb >> 8) & 255) / 255f;
        float b = (rgb & 255) / 255f;
        body.render(pose, buf, light, overlay, r, g, b, 1f);
        glass.render(pose, buf, light, overlay, 0.18f, 0.26f, 0.34f, 1f);
        lights.render(pose, buf, light, overlay, 1f, 0.95f, 0.75f, 1f);
        for (ModelPart w : wheels) {
            w.xRot = wheelRot;
            w.render(pose, buf, light, overlay, 0.08f, 0.08f, 0.1f, 1f);
        }
    }
}
