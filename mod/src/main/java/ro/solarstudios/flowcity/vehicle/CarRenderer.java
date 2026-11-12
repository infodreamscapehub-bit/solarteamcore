package ro.solarstudios.flowcity.vehicle;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/** Deseneaza masina; rotile se invart dupa distanta parcursa (calculata local, nu se sincronizeaza). */
public final class CarRenderer extends EntityRenderer<CarEntity> {
    private final CarModel model;
    private final java.util.Map<Integer, double[]> wheelState = new java.util.HashMap<>();
    private final java.util.Map<Integer, double[]> speedState = new java.util.HashMap<>();

    public CarRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.model = new CarModel(ctx.bakeLayer(CarModel.LAYER));
        this.shadowRadius = 1.2f;
    }

    @Override
    public ResourceLocation getTextureLocation(CarEntity e) {
        return CarModel.TEXTURE;
    }

    @Override
    public void render(CarEntity e, float yaw, float pt, PoseStack pose, MultiBufferSource buffers, int light) {
        pose.pushPose();
        pose.translate(0.0, 1.5, 0.0);
        pose.mulPose(Axis.YP.rotationDegrees(180f - yaw));
        pose.scale(-1f, -1f, 1f);
        // distanta parcursa de la ultimul cadru (semnata dupa directia masinii)
        double[] st = wheelState.computeIfAbsent(e.getId(), k -> new double[] {0, e.getX(), e.getZ()});
        double dx = e.getX() - st[1];
        double dz = e.getZ() - st[2];
        double yawRad = Math.toRadians(e.getYRot());
        double along = dx * -Math.sin(yawRad) + dz * Math.cos(yawRad);
        if (Math.abs(along) < 3) st[0] += along;
        st[1] = e.getX();
        st[2] = e.getZ();
        float wheelRot = (float) (st[0] / 0.4);   // raza rotii ~0.3 blocuri
        // stopuri: soferul local stie cand franeaza; la ceilalti ghicim din scaderea vitezei
        double[] sp = speedState.computeIfAbsent(e.getId(), k -> new double[] {0, 0});
        double v = Math.abs(along);
        boolean brake;
        if (e.getControllingPassenger() == net.minecraft.client.Minecraft.getInstance().player) brake = e.braking();
        else {
            if (v > 0.001 || sp[0] > 0.001) sp[1] = (sp[0] - v > 0.002) ? 20 : Math.max(0, sp[1] - 1);
            brake = sp[1] > 0;
        }
        sp[0] = v;
        int beacon = 0;
        if (e.flag(CarEntity.F_BEACON)) beacon = ((System.currentTimeMillis() / 180) % 2 == 0) ? 1 : 2;
        boolean flash = e.flash > 0 && (e.flash / 3) % 2 == 0;
        VertexConsumer vc = buffers.getBuffer(RenderType.entityCutoutNoCull(CarModel.TEXTURE));
        model.render(pose, vc, light, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, e.getColor(), e.getVariant(), wheelRot,
            e.flag(CarEntity.F_LIGHTS), brake, beacon, flash);
        pose.popPose();
        super.render(e, yaw, pt, pose, buffers, light);
    }
}
