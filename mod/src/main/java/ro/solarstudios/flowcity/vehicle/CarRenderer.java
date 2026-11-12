package ro.solarstudios.flowcity.vehicle;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import ro.solarstudios.flowcity.FlowCity;

/**
 * Deseneaza masinile: modelul caroseriei, livreaua si inscriptiile laterale (din car_decals.png).
 * Rotile se invart dupa distanta parcursa (calculata local).
 */
public final class CarRenderer extends EntityRenderer<CarEntity> {
    private static final ResourceLocation DECALS = new ResourceLocation(FlowCity.MODID, "textures/entity/car_decals.png");
    private final CarModel[] models = new CarModel[VehicleTypes.BODIES];
    private final Map<Integer, double[]> wheelState = new HashMap<>();
    private final Map<Integer, double[]> speedState = new HashMap<>();

    public CarRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        for (int i = 0; i < models.length; i++) models[i] = new CarModel(ctx.bakeLayer(CarModel.LAYERS[i]));
        this.shadowRadius = 1.2f;
    }

    @Override
    public ResourceLocation getTextureLocation(CarEntity e) {
        return CarModel.TEXTURE;
    }

    @Override
    public void render(CarEntity e, float yaw, float pt, PoseStack pose, MultiBufferSource buffers, int light) {
        VehicleTypes.Type type = VehicleTypes.get(e.getVariant());
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
        VehicleTypes.Spec spec = VehicleTypes.SPECS[type.body];
        float wheelRot = (float) (st[0] / (spec.wheelR / 16.0));
        // stopuri: soferul local stie cand franeaza; la ceilalti ghicim din scaderea vitezei
        double[] sp = speedState.computeIfAbsent(e.getId(), k -> new double[] {0, 0});
        double v = Math.abs(along);
        boolean brake;
        if (e.getControllingPassenger() == Minecraft.getInstance().player) {
            brake = e.braking();
        } else {
            if (v > 0.001 || sp[0] > 0.001) sp[1] = (sp[0] - v > 0.002) ? 20 : Math.max(0, sp[1] - 1);
            brake = sp[1] > 0;
        }
        sp[0] = v;
        int beacon = 0;
        if (e.flag(CarEntity.F_BEACON)) beacon = ((System.currentTimeMillis() / 180) % 2 == 0) ? 1 : 2;
        boolean flash = e.flash > 0 && (e.flash / 3) % 2 == 0;

        VertexConsumer vc = buffers.getBuffer(RenderType.entityCutoutNoCull(CarModel.TEXTURE));
        models[type.body].render(pose, vc, light, OverlayTexture.NO_OVERLAY, e.getColor(), type, wheelRot,
            e.flag(CarEntity.F_LIGHTS), brake, beacon, flash);
        if (type.livery.decal >= 0) decals(pose, buffers.getBuffer(RenderType.entityCutoutNoCull(DECALS)), spec, type.livery.decal, light);
        pose.popPose();
        super.render(e, yaw, pt, pose, buffers, light);
    }

    /**
     * Inscriptia pe ambele laterale. In spatiul modelului +X e partea stanga a soferului, +Y e in jos, fata e -Z.
     * Pe stanga textul se citeste din fata spre spate, pe dreapta din spate spre fata.
     */
    private static void decals(PoseStack pose, VertexConsumer vc, VehicleTypes.Spec s, int row, int light) {
        boolean cargo = s.cargoFrom < s.l;
        float len = cargo ? (s.l - s.cargoFrom) - 6 : s.l * 1.15f;
        float h = len / 6.4f;
        float cz = cargo ? (s.cargoFrom + s.l) / 2f : 2f;
        float cy = cargo ? (s.roof + s.belt) / 2f : (s.belt + s.sill) / 2f + 0.2f;
        if (!cargo && h > (s.sill - s.belt) - 1) {
            h = (s.sill - s.belt) - 1;
            len = h * 6.4f;
        }
        float z0 = (cz - len / 2) / 16f, z1 = (cz + len / 2) / 16f;
        float y0 = (cy - h / 2) / 16f, y1 = (cy + h / 2) / 16f;
        float v0 = row * 40 / 256f, v1 = (row * 40 + 40) / 256f;
        float xl = (s.w + 0.35f) / 16f;
        Matrix4f m = pose.last().pose();
        Matrix3f n = pose.last().normal();
        // stanga (+X): fata -> spate
        vert(vc, m, n, xl, y0, z0, 0, v0, light, 1);
        vert(vc, m, n, xl, y1, z0, 0, v1, light, 1);
        vert(vc, m, n, xl, y1, z1, 1, v1, light, 1);
        vert(vc, m, n, xl, y0, z1, 1, v0, light, 1);
        // dreapta (-X): spate -> fata
        vert(vc, m, n, -xl, y0, z1, 0, v0, light, -1);
        vert(vc, m, n, -xl, y1, z1, 0, v1, light, -1);
        vert(vc, m, n, -xl, y1, z0, 1, v1, light, -1);
        vert(vc, m, n, -xl, y0, z0, 1, v0, light, -1);
    }

    private static void vert(VertexConsumer vc, Matrix4f m, Matrix3f n, float x, float y, float z, float u, float v, int light, float nx) {
        vc.vertex(m, x, y, z).color(255, 255, 255, 255).uv(u, v).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(n, nx, 0, 0).endVertex();
    }
}
