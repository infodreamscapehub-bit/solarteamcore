#!/usr/bin/env python3
"""Unealta de portare 1.16.5 -> 1.20.1 pentru FlowCityCore.

Copiaza fisiere din mod116/ in mod/ si rescrie API-ul vechi (numele de pachete
din 1.16.5) in cel din 1.20.1. Tabelul de mai jos a fost derivat din fisierele
care exista in ambele module (adevarul din repo), completat cu redenumirile
cunoscute din reorganizarea de pachete 1.17.

Folosire:
    python3 tools/port116to120.py gps                      # tot pachetul gps
    python3 tools/port116to120.py client/ui/FUi.java       # un fisier
    python3 tools/port116to120.py --check gps              # doar arata ce ramane neconvertit
    python3 tools/port116to120.py --stdout x/Y.java > /tmp/Y.java   # fara sa scrie peste tinta

ATENTIE: unealta SUPRASCRIE fisierul din mod/. Daca tinta are deja continut care exista
doar pe 1.20.1 (FlowCity.java, ClientHooks.java, ClientInit.java, ModContent.java), nu o
rula peste el: foloseste --stdout si imbina manual.

Dupa rulare TREBUIE verificat manual: unealta nu rezolva schimbarile de
semnatura (vezi PORT-1.20.1.md, sectiunea "Ce nu face unealta").
"""
import os
import re
import shutil
import sys

SRC = "mod116/src/main/java/ro/solarstudios/flowcity"
DST = "mod/src/main/java/ro/solarstudios/flowcity"

# Nume complet de clasa: 1.16.5 -> 1.20.1.
TYPES = {
    # --- shim-urile proprii devin clase reale pe 1.20.1 ---
    "ro.solarstudios.flowcity.compat.GuiGraphics": "net.minecraft.client.gui.GuiGraphics",
    "ro.solarstudios.flowcity.compat.RegisterKeyMappingsEvent":
        "net.minecraftforge.client.event.RegisterKeyMappingsEvent",
    # --- registre dinamice (1.16.5: RegistryKey + Registry.*_REGISTRY) ---
    "net.minecraft.util.RegistryKey": "net.minecraft.resources.ResourceKey",
    "net.minecraft.util.registry.Registry": "net.minecraft.core.registries.Registries",
    "net.minecraft.util.registry.DynamicRegistries": "net.minecraft.core.RegistryAccess",
    # --- blaze3d ---
    "com.mojang.blaze3d.matrix.MatrixStack": "com.mojang.blaze3d.vertex.PoseStack",
    "com.mojang.blaze3d.vertex.IVertexBuilder": "com.mojang.blaze3d.vertex.VertexConsumer",
    "net.minecraft.client.renderer.BufferBuilder": "com.mojang.blaze3d.vertex.BufferBuilder",
    "net.minecraft.client.renderer.Tessellator": "com.mojang.blaze3d.vertex.Tesselator",
    "net.minecraft.client.renderer.vertex.DefaultVertexFormats": "com.mojang.blaze3d.vertex.DefaultVertexFormat",
    "net.minecraft.client.renderer.texture.NativeImage": "com.mojang.blaze3d.platform.NativeImage",
    "net.minecraft.client.renderer.RenderHelper": "com.mojang.blaze3d.platform.Lighting",
    "net.minecraft.client.util.InputMappings": "com.mojang.blaze3d.platform.InputConstants",
    "net.minecraft.client.MainWindow": "com.mojang.blaze3d.platform.Window",
    # --- client: gui ---
    "net.minecraft.client.gui.FontRenderer": "net.minecraft.client.gui.Font",
    "net.minecraft.client.gui.AbstractGui": "net.minecraft.client.gui.GuiComponent",
    "net.minecraft.client.gui.screen.Screen": "net.minecraft.client.gui.screens.Screen",
    "net.minecraft.client.gui.screen.ChatScreen": "net.minecraft.client.gui.screens.ChatScreen",
    "net.minecraft.client.gui.screen.ConnectingScreen": "net.minecraft.client.gui.screens.ConnectScreen",
    "net.minecraft.client.gui.screen.MainMenuScreen": "net.minecraft.client.gui.screens.TitleScreen",
    "net.minecraft.client.gui.screen.OptionsScreen": "net.minecraft.client.gui.screens.OptionsScreen",
    "net.minecraft.client.gui.screen.inventory.ContainerScreen":
        "net.minecraft.client.gui.screens.inventory.AbstractContainerScreen",
    "net.minecraft.client.gui.screen.inventory.InventoryScreen":
        "net.minecraft.client.gui.screens.inventory.InventoryScreen",
    "net.minecraft.client.gui.widget.TextFieldWidget": "net.minecraft.client.gui.components.EditBox",
    # --- client: restul ---
    "net.minecraft.client.entity.player.ClientPlayerEntity": "net.minecraft.client.player.LocalPlayer",
    "net.minecraft.client.world.ClientWorld": "net.minecraft.client.multiplayer.ClientLevel",
    "net.minecraft.client.network.play.ClientPlayNetHandler": "net.minecraft.client.multiplayer.ClientPacketListener",
    "net.minecraft.client.network.ServerPinger": "net.minecraft.client.multiplayer.ServerStatusPinger",
    "net.minecraft.client.renderer.ActiveRenderInfo": "net.minecraft.client.Camera",
    "net.minecraft.client.renderer.IRenderTypeBuffer": "net.minecraft.client.renderer.MultiBufferSource",
    "net.minecraft.client.renderer.entity.EntityRendererManager":
        "net.minecraft.client.renderer.entity.EntityRenderDispatcher",
    "net.minecraft.client.renderer.model.ItemCameraTransforms": "net.minecraft.world.item.ItemDisplayContext",
    "net.minecraft.client.audio.ISound": "net.minecraft.client.resources.sounds.SoundInstance",
    "net.minecraft.client.audio.SimpleSound": "net.minecraft.client.resources.sounds.SimpleSoundInstance",
    "net.minecraft.client.settings.KeyBinding": "net.minecraft.client.KeyMapping",
    "net.minecraft.client.util.ITooltipFlag": "net.minecraft.world.item.TooltipFlag",
    "net.minecraft.util.ScreenShotHelper": "net.minecraft.client.Screenshot",
    # --- text ---
    "net.minecraft.util.text.ITextComponent": "net.minecraft.network.chat.Component",
    "net.minecraft.util.text.IFormattableTextComponent": "net.minecraft.network.chat.MutableComponent",
    "net.minecraft.util.text.StringTextComponent": "net.minecraft.network.chat.Component",
    "net.minecraft.util.text.TranslationTextComponent": "net.minecraft.network.chat.Component",
    "net.minecraft.util.text.Style": "net.minecraft.network.chat.Style",
    "net.minecraft.util.text.TextFormatting": "net.minecraft.ChatFormatting",
    # --- core / util ---
    "net.minecraft.util.ResourceLocation": "net.minecraft.resources.ResourceLocation",
    "net.minecraft.util.Direction": "net.minecraft.core.Direction",
    "net.minecraft.util.Util": "net.minecraft.Util",
    "net.minecraft.util.Hand": "net.minecraft.world.InteractionHand",
    "net.minecraft.util.ActionResult": "net.minecraft.world.InteractionResultHolder",
    "net.minecraft.util.ActionResultType": "net.minecraft.world.InteractionResult",
    "net.minecraft.util.SoundEvent": "net.minecraft.sounds.SoundEvent",
    "net.minecraft.util.SoundEvents": "net.minecraft.sounds.SoundEvents",
    "net.minecraft.util.SoundCategory": "net.minecraft.sounds.SoundSource",
    "net.minecraft.util.math.BlockPos": "net.minecraft.core.BlockPos",
    "net.minecraft.util.math.MathHelper": "net.minecraft.util.Mth",
    "net.minecraft.util.math.AxisAlignedBB": "net.minecraft.world.phys.AABB",
    "net.minecraft.util.math.RayTraceResult": "net.minecraft.world.phys.HitResult",
    "net.minecraft.util.math.BlockRayTraceResult": "net.minecraft.world.phys.BlockHitResult",
    "net.minecraft.util.math.EntityRayTraceResult": "net.minecraft.world.phys.EntityHitResult",
    "net.minecraft.util.math.vector.Vector3d": "net.minecraft.world.phys.Vec3",
    "net.minecraft.util.math.vector.Vector3f": "org.joml.Vector3f",
    "net.minecraft.util.math.vector.Matrix4f": "org.joml.Matrix4f",
    # --- nbt ---
    "net.minecraft.nbt.CompoundNBT": "net.minecraft.nbt.CompoundTag",
    "net.minecraft.nbt.INBT": "net.minecraft.nbt.Tag",
    "net.minecraft.nbt.ListNBT": "net.minecraft.nbt.ListTag",
    "net.minecraft.nbt.IntArrayNBT": "net.minecraft.nbt.IntArrayTag",
    # --- retea ---
    "net.minecraft.network.PacketBuffer": "net.minecraft.network.FriendlyByteBuf",
    "net.minecraft.network.play.client.CCustomPayloadPacket":
        "net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket",
    # --- lume / entitati ---
    "net.minecraft.world.World": "net.minecraft.world.level.Level",
    "net.minecraft.world.server.ServerWorld": "net.minecraft.server.level.ServerLevel",
    "net.minecraft.world.chunk.IChunk": "net.minecraft.world.level.chunk.ChunkAccess",
    "net.minecraft.world.chunk.ChunkStatus": "net.minecraft.world.level.chunk.ChunkStatus",
    "net.minecraft.world.gen.Heightmap": "net.minecraft.world.level.levelgen.Heightmap",
    "net.minecraft.world.storage.WorldSavedData": "net.minecraft.world.level.saveddata.SavedData",
    "net.minecraft.entity.Entity": "net.minecraft.world.entity.Entity",
    "net.minecraft.entity.LivingEntity": "net.minecraft.world.entity.LivingEntity",
    "net.minecraft.entity.EntityType": "net.minecraft.world.entity.EntityType",
    "net.minecraft.entity.EntityClassification": "net.minecraft.world.entity.MobCategory",
    "net.minecraft.entity.player.PlayerEntity": "net.minecraft.world.entity.player.Player",
    "net.minecraft.entity.player.ServerPlayerEntity": "net.minecraft.server.level.ServerPlayer",
    # --- blocuri / iteme ---
    "net.minecraft.block.Block": "net.minecraft.world.level.block.Block",
    "net.minecraft.block.BlockState": "net.minecraft.world.level.block.state.BlockState",
    "net.minecraft.block.AbstractBlock": "net.minecraft.world.level.block.state.BlockBehaviour",
    "net.minecraft.block.HorizontalBlock": "net.minecraft.world.level.block.HorizontalDirectionalBlock",
    "net.minecraft.block.material.Material": "net.minecraft.world.level.material.Material",
    "net.minecraft.block.material.MaterialColor": "net.minecraft.world.level.material.MapColor",
    "net.minecraft.state.StateContainer": "net.minecraft.world.level.block.state.StateDefinition",
    "net.minecraft.item.Item": "net.minecraft.world.item.Item",
    "net.minecraft.item.Items": "net.minecraft.world.item.Items",
    "net.minecraft.item.ItemStack": "net.minecraft.world.item.ItemStack",
    "net.minecraft.item.BlockItem": "net.minecraft.world.item.BlockItem",
    "net.minecraft.item.ItemGroup": "net.minecraft.world.item.CreativeModeTab",
    "net.minecraft.item.BlockItemUseContext": "net.minecraft.world.item.context.BlockPlaceContext",
    "net.minecraft.inventory.container.Container": "net.minecraft.world.inventory.AbstractContainerMenu",
    "net.minecraft.inventory.container.PlayerContainer": "net.minecraft.world.inventory.InventoryMenu",
    "net.minecraft.inventory.container.Slot": "net.minecraft.world.inventory.Slot",
    # --- comenzi ---
    "net.minecraft.command.Commands": "net.minecraft.commands.Commands",
    "net.minecraft.command.CommandSource": "net.minecraft.commands.CommandSourceStack",
    "net.minecraft.command.arguments.EntityArgument": "net.minecraft.commands.arguments.EntityArgument",
    # --- forge ---
    "net.minecraftforge.fml.network.NetworkRegistry": "net.minecraftforge.network.NetworkRegistry",
    "net.minecraftforge.fml.network.NetworkEvent": "net.minecraftforge.network.NetworkEvent",
    "net.minecraftforge.fml.network.PacketDistributor": "net.minecraftforge.network.PacketDistributor",
    "net.minecraftforge.fml.network.simple.SimpleChannel": "net.minecraftforge.network.simple.SimpleChannel",
    "net.minecraftforge.fml.network.event.EventNetworkChannel": "net.minecraftforge.network.event.EventNetworkChannel",
    "net.minecraftforge.fml.RegistryObject": "net.minecraftforge.registries.RegistryObject",
    "net.minecraftforge.fml.server.ServerLifecycleHooks": "net.minecraftforge.server.ServerLifecycleHooks",
    "net.minecraftforge.fml.common.ObfuscationReflectionHelper":
        "net.minecraftforge.fml.util.ObfuscationReflectionHelper",
    "net.minecraftforge.fml.ExtensionPoint": "net.minecraftforge.fml.IExtensionPoint",
    "net.minecraftforge.fml.packs.ResourcePackLoader": "net.minecraftforge.resource.ResourcePackLoader",
    "net.minecraftforge.client.event.GuiOpenEvent": "net.minecraftforge.client.event.ScreenEvent",
    "net.minecraftforge.client.event.RenderGameOverlayEvent": "net.minecraftforge.client.event.RenderGuiEvent",
    "net.minecraftforge.client.event.RenderNameplateEvent": "net.minecraftforge.client.event.RenderNameTagEvent",
    "net.minecraftforge.client.event.RenderWorldLastEvent": "net.minecraftforge.client.event.RenderLevelStageEvent",
    "net.minecraftforge.event.entity.EntityJoinWorldEvent": "net.minecraftforge.event.entity.EntityJoinLevelEvent",
}

# Nume simple de clasa (dupa ce importurile au fost rescrise).
SIMPLE = {
    "MatrixStack": "PoseStack",
    "IVertexBuilder": "VertexConsumer",
    "Tessellator": "Tesselator",
    "DefaultVertexFormats": "DefaultVertexFormat",
    "FontRenderer": "Font",
    "AbstractGui": "GuiComponent",
    "MainWindow": "Window",
    "ClientPlayerEntity": "LocalPlayer",
    "ClientWorld": "ClientLevel",
    "ClientPlayNetHandler": "ClientPacketListener",
    "ServerPinger": "ServerStatusPinger",
    "ActiveRenderInfo": "Camera",
    "IRenderTypeBuffer": "MultiBufferSource",
    "EntityRendererManager": "EntityRenderDispatcher",
    "RenderHelper": "Lighting",
    "InputMappings": "InputConstants",
    "KeyBinding": "KeyMapping",
    "ITooltipFlag": "TooltipFlag",
    "ScreenShotHelper": "Screenshot",
    "ISound": "SoundInstance",
    "SimpleSound": "SimpleSoundInstance",
    "TextFieldWidget": "EditBox",
    "ContainerScreen": "AbstractContainerScreen",
    "ConnectingScreen": "ConnectScreen",
    "MainMenuScreen": "TitleScreen",
    "ITextComponent": "Component",
    "IFormattableTextComponent": "MutableComponent",
    "TextFormatting": "ChatFormatting",
    "CompoundNBT": "CompoundTag",
    "ListNBT": "ListTag",
    "IntArrayNBT": "IntArrayTag",
    "INBT": "Tag",
    "PacketBuffer": "FriendlyByteBuf",
    "CCustomPayloadPacket": "ServerboundCustomPayloadPacket",
    "World": "Level",
    "ServerWorld": "ServerLevel",
    "IChunk": "ChunkAccess",
    "WorldSavedData": "SavedData",
    "EntityClassification": "MobCategory",
    "PlayerEntity": "Player",
    "ServerPlayerEntity": "ServerPlayer",
    "AbstractBlock": "BlockBehaviour",
    "HorizontalBlock": "HorizontalDirectionalBlock",
    "MaterialColor": "MapColor",
    "StateContainer": "StateDefinition",
    "ItemGroup": "CreativeModeTab",
    "BlockItemUseContext": "BlockPlaceContext",
    "PlayerContainer": "InventoryMenu",
    "CommandSource": "CommandSourceStack",
    "MathHelper": "Mth",
    "AxisAlignedBB": "AABB",
    "RayTraceResult": "HitResult",
    "BlockRayTraceResult": "BlockHitResult",
    "EntityRayTraceResult": "EntityHitResult",
    "Vector3d": "Vec3",
    "Hand": "InteractionHand",
    "ActionResultType": "InteractionResult",
    "ActionResult": "InteractionResultHolder",
    "SoundCategory": "SoundSource",
    "ItemCameraTransforms": "ItemDisplayContext",
    "GuiOpenEvent": "ScreenEvent.Opening",
    "RenderGameOverlayEvent": "RenderGuiEvent",
    "RenderNameplateEvent": "RenderNameTagEvent",
    "RenderWorldLastEvent": "RenderLevelStageEvent",
    "EntityJoinWorldEvent": "EntityJoinLevelEvent",
    "ExtensionPoint": "IExtensionPoint",
}

# Rescrieri la nivel de cod (expresii regulate aplicate dupa tipuri).
CODE = [
    # texte: StringTextComponent / TranslationTextComponent nu mai exista
    (r"new\s+StringTextComponent\s*\(", "Component.literal("),
    (r"new\s+TranslationTextComponent\s*\(", "Component.translatable("),
    (r"StringTextComponent\.EMPTY", "Component.empty()"),
    # font: API-ul de desen
    (r"\.getStringWidth\(", ".width("),
    (r"\bfontWidth\b", "width"),
    # MathHelper -> Mth (daca a ramas calificat)
    (r"\bMth\.lerp\b", "Mth.lerp"),
    # registre dinamice: Registry.X_REGISTRY -> Registries.X
    (r"\bRegistries\.([A-Z_]+)_REGISTRY\b", r"Registries.\1"),
    # taste
    (r"ClientRegistry\.registerKeyBinding\(", "/* 1.20.1: RegisterKeyMappingsEvent */ event.register("),
    # HUD: pe 1.20.1 evenimentul aduce direct un GuiGraphics, iar ElementType nu mai exista
    (r"\s*if \([a-z]+\.getType\(\) != RenderGuiEvent\.ElementType\.ALL\) return;\n", "\n"),
    (r"new GuiGraphics\((\w+)\.getMatrixStack\(\)\)", r"\1.getGuiGraphics()"),
    (r"(\w+)\.getMatrixStack\(\)", r"\1.getGuiGraphics().pose()"),
    # randarea in lume: RenderWorldLastEvent -> RenderLevelStageEvent
    (r"(\w+)\.getPartialTicks\(\)", r"\1.getPartialTick()"),
]

# Metode redenumite care NU se pot rescrie automat fara context (doar avertisment).
WARN = [
    ("getTextureManager().bind(", "1.20.1: RenderSystem.setShaderTexture(0, tex)"),
    ("RenderSystem.pushMatrix", "1.20.1: foloseste g.pose().pushPose()"),
    ("RenderSystem.popMatrix", "1.20.1: foloseste g.pose().popPose()"),
    ("RenderSystem.multMatrix", "1.20.1: nu mai exista; matricea vine din GuiGraphics"),
    ("renderAndDecorateItem", "1.20.1: g.renderItem(stack, x, y)"),
    ("func_", "nume SRG: inlocuieste cu numele oficial"),
    ("GuiOpenEvent", "1.20.1: ScreenEvent.Opening, cu setNewScreen/getNewScreen"),
    ("RenderLevelStageEvent",
     "1.20.1: adauga if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return; si foloseste e.getPoseStack()"),
    ("Material.", "1.20.1: BlockBehaviour.Properties.of() nu mai ia Material"),
    ("getRegistryName()", "1.20.1: ForgeRegistries.<REGISTRU>.getKey(obiect)"),
]


def convert(text):
    for old, new in sorted(TYPES.items(), key=lambda kv: -len(kv[0])):
        text = text.replace(old, new)
    for old, new in sorted(SIMPLE.items(), key=lambda kv: -len(kv[0])):
        text = re.sub(r"\b" + re.escape(old) + r"\b", new, text)
    for pat, rep in CODE:
        text = re.sub(pat, rep, text)
    # importuri duplicate dupa rescriere (ex. ITextComponent si StringTextComponent -> Component)
    lines, seen, out = text.split("\n"), set(), []
    for line in lines:
        m = re.match(r"\s*import\s+(static\s+)?([\w.]+);", line)
        if m:
            if m.group(2) in seen:
                continue
            seen.add(m.group(2))
        out.append(line)
    return "\n".join(out)


def warnings(path, text):
    found = []
    for needle, hint in WARN:
        if needle in text:
            found.append(f"  {path}: contine '{needle}' -> {hint}")
    return found


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    check = "--check" in sys.argv
    to_stdout = "--stdout" in sys.argv
    if not args:
        print(__doc__)
        return 1
    files, warns = [], []
    for target in args:
        src = os.path.join(SRC, target)
        if os.path.isdir(src):
            for root, _, fs in os.walk(src):
                files += [os.path.join(root, f) for f in fs if f.endswith(".java")]
        elif os.path.isfile(src):
            files.append(src)
        else:
            print(f"lipseste: {src}")
            return 1
    for src in files:
        dst = src.replace(SRC, DST)
        text = convert(open(src, encoding="utf-8").read())
        warns += warnings(os.path.relpath(dst), text)
        if to_stdout:
            print(text)
            continue
        if not check:
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            open(dst, "w", encoding="utf-8").write(text)
    if to_stdout:
        return 0
    print(f"{'verificate' if check else 'portate'}: {len(files)} fisiere")
    if warns:
        print("DE VERIFICAT MANUAL:")
        print("\n".join(sorted(set(warns))))
    return 0


if __name__ == "__main__":
    sys.exit(main())
