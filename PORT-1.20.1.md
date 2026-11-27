# Portarea FlowCityCore pe Minecraft 1.20.1

Documentul e **contractul** dintre etapele de portare. Cine continua portarea il citeste
primul si il tine la zi (bifeaza etapele facute, adauga diferentele de API intalnite).

## Ce avem

| Modul | Versiune | Fisiere | Linii | Stare |
|---|---|---|---|---|
| `mod116/` | Forge 1.16.5 (official mappings) | 159 | ~47 000 | **sursa**, complet |
| `mod/` | Forge 1.20.1-47.3.0 | 83 | ~14 500 | **tinta**, in urma |
| `plugin/` | Bukkit (Arclight/Mohist) | — | — | **comun**, nu se atinge |

Pluginul (Bridge) e acelasi pentru ambele versiuni. Protocolul JSON dintre mod si plugin
**trebuie pastrat identic** — altfel serverul nu mai vorbeste cu clientul.

### Vestea buna

`mod116` a fost scris **deliberat in stil 1.20.1**: are un strat de compatibilitate
(`compat/GuiGraphics`, `compat/RegisterKeyMappingsEvent`) care imita API-ul din 1.20.1 peste
`MatrixStack` + `AbstractGui`. De aceea portarea e in mare parte **mecanica**: se schimba
importurile si numele de pachete, nu logica ecranelor.

Pe 1.20.1 cele doua clase din `compat/` **nu se copiaza** — se folosesc cele reale din vanilla/Forge.

## Unealta

`tools/port116to120.py` copiaza fisiere din `mod116/` in `mod/` si rescrie API-ul.

```bash
python3 tools/port116to120.py --check gps     # arata doar ce ramane de facut manual
python3 tools/port116to120.py gps             # porteaza pachetul
python3 tools/port116to120.py client/ui/FUi.java
```

Tabelul de tipuri din ea a fost **derivat din repo** (69 de fisiere exista in ambele module;
importurile lor diferite dau maparea exacta), completat cu redenumirile din reorganizarea de
pachete 1.17. Deci nu e ghicit.

### Capcana: uneori `mod/` e mai nou decat `mod116/`

Unealta suprascrie fisierul din `mod/`. Pentru fisierele care exista in ambele module,
**da intai `diff` si pastreaza partea mai avansata** — altfel faci regresie. Cazuri intalnite:

| Fisier | De ce nu se suprascrie |
|---|---|
| `client/ui/FlowScreen.java` | pe 1.20.1 avea deja `render(GuiGraphics, …)` si `EditBox.setX/setY`; varianta din 1.16.5 le-ar fi dat inapoi la `PoseStack` si campurile publice `x`/`y` |
| `client/ui/FlowSounds.java` | pe 1.20.1 `SimpleSoundInstance` cere `RandomSource`, iar enumul e `SoundInstance.Attenuation`, nu `AttenuationType` |
| `FlowCity.java`, `client/ClientHooks.java`, `client/ClientInit.java`, `content/ModContent.java` | au continut care exista doar pe 1.20.1 (blocurile GPS, `maps/`); se imbina manual, cu `--stdout` |

### Diferentele gasite la etapele 2-3b (unealta nu le acopera)

| Eroare la compilare | Cauza si rezolvarea |
|---|---|
| `yRot has private access in Entity` | campurile de rotatie sunt private: `getYRot()` (`yRotO` a ramas public) |
| `x has private access in AbstractWidget` | widget-urile isi ascund pozitia: `setX()` / `setY()` |
| `PoseStack cannot be converted to GuiGraphics` | `widget.render(…)` primeste `GuiGraphics`, nu `g.pose()` |
| `Component is abstract` | `new Component(s)` → `Component.literal(s)` |
| `cannot find symbol: class Mutable` | `BlockPos.Mutable` → `BlockPos.MutableBlockPos` |
| `cannot find symbol: getRegistryName()` | Forge l-a scos: `ForgeRegistries.BLOCKS.getKey(block)` |
| `isAir cannot be applied to given types` | `state.isAir(level, pos)` → `state.isAir()` |
| `Reference<SoundEvent> cannot be converted` | o parte din `SoundEvents.*` sunt `Holder<SoundEvent>`: `.value()`, sau supraincarcare |
| `cannot find symbol: variable ElementType` | barele vanilla se ascund cu `RenderGuiOverlayEvent.Pre` + `VanillaGuiOverlay.PLAYER_HEALTH.type()`; `RenderGuiEvent.Post` nu mai are `getType()` |
| dalele hartii nu se leaga | `getTextureManager().bind(rl)` → `RenderSystem.setShader(GameRenderer::getPositionTexShader)` + `setShaderTexture(0, rl)`; `color4f` → `setShaderColor`; `enableTexture`, `disableAlphaTest` si `shadeModel` nu mai exista; `b.begin(int, …)` → `b.begin(VertexFormat.Mode, …)` |
| `RenderLevelStageEvent` | `e.getPoseStack()` (nu `getGuiGraphics()`) si verificarea `Stage.AFTER_TRANSLUCENT_BLOCKS`, altfel desenezi de mai multe ori pe cadru |

### Dependente intre pachete, rupte cu hook-uri

Doua pachete se cereau unul pe altul, deci nu se puteau porta in nicio ordine:

| Hook | Cine il seteaza | De ce |
|---|---|---|
| `MiniMapRenderer.statusStrip` | `HypeClient.register()` | `gps` lasa loc sub minimap randului compact de status, dar `StatusHud` citeste `MiniMapSettings` |
| `NotifyScreen.openApp` | pachetul `phone`, la etapa 4 | apasarea pe o notificare deschide aplicatia; telefonul nou inca nu e portat |

`GaragesClient` a primit clasa `S` si lista `stations` (le foloseste lista de locatii din `gps`);
serverul le trimitea deja, clientul vechi le ignora.

### Ce NU face unealta

Verifica manual dupa fiecare rulare (le raporteaza singura cu `--check`):

| Situatie | Ce faci |
|---|---|
| `mc.getTextureManager().bind(tex)` | `RenderSystem.setShaderTexture(0, tex)` |
| `RenderSystem.pushMatrix/popMatrix/multMatrix` | nu mai exista; matricea vine din `GuiGraphics.pose()` |
| `renderAndDecorateItem(stack, x, y)` | `g.renderItem(stack, x, y)` |
| `RenderLevelStageEvent` | adauga `if (e.getStage() != Stage.AFTER_TRANSLUCENT_BLOCKS) return;` si foloseste `e.getPoseStack()` |
| `BlockBehaviour.Properties.of(Material...)` | pe 1.20.1 `of()` nu mai ia `Material`; pune `.mapColor(...)` |
| Semnaturi de `Screen` | `render(GuiGraphics, int, int, float)`, `renderBackground(GuiGraphics)` |
| `Component.literal` vs `.literal("")` | `StringTextComponent.EMPTY` → `Component.empty()` |

## Diferente de API 1.16.5 -> 1.20.1 (cele intalnite efectiv)

Tabelul complet, executabil, e in `tools/port116to120.py` (`TYPES` si `SIMPLE`). Rezumat:

| Zona | 1.16.5 | 1.20.1 |
|---|---|---|
| desen GUI | `compat.GuiGraphics` (shim peste `MatrixStack`) | `net.minecraft.client.gui.GuiGraphics` |
| matrice | `com.mojang.blaze3d.matrix.MatrixStack` | `com.mojang.blaze3d.vertex.PoseStack` |
| font | `client.gui.FontRenderer` | `client.gui.Font` |
| text | `util.text.ITextComponent`, `new StringTextComponent(s)` | `network.chat.Component`, `Component.literal(s)` |
| culori text | `util.text.TextFormatting` | `net.minecraft.ChatFormatting` |
| lume | `world.World`, `world.server.ServerWorld` | `world.level.Level`, `server.level.ServerLevel` |
| jucator | `entity.player.PlayerEntity`, `ServerPlayerEntity` | `world.entity.player.Player`, `server.level.ServerPlayer` |
| client | `client.entity.player.ClientPlayerEntity`, `client.world.ClientWorld` | `client.player.LocalPlayer`, `client.multiplayer.ClientLevel` |
| nbt | `CompoundNBT`, `ListNBT`, `INBT` | `CompoundTag`, `ListTag`, `Tag` |
| retea | `network.PacketBuffer` | `network.FriendlyByteBuf` |
| mate | `util.math.MathHelper`, `Vector3d`, `AxisAlignedBB` | `util.Mth`, `world.phys.Vec3`, `world.phys.AABB` |
| vectori | `util.math.vector.Vector3f/Matrix4f` | `org.joml.Vector3f/Matrix4f` |
| taste | `client.settings.KeyBinding` + `ClientRegistry` | `client.KeyMapping` + `RegisterKeyMappingsEvent` |
| HUD | `RenderGameOverlayEvent.Post` + `ElementType.ALL` + `getMatrixStack()` | `RenderGuiEvent.Post` + `getGuiGraphics()` |
| randare in lume | `RenderWorldLastEvent` | `RenderLevelStageEvent` (+ verificare de etapa) |
| tab creativ | `ItemGroup` + `Item.Properties().tab(TAB)` | `CreativeModeTab.builder()` inregistrat in `Registries.CREATIVE_MODE_TAB`, itemele intra prin `displayItems` |
| Forge retea | `fml.network.*` | `net.minecraftforge.network.*` |
| Forge registre | `fml.RegistryObject` | `registries.RegistryObject` |
| ecrane | `client.gui.screen.*` | `client.gui.screens.*` |
| casete text | `client.gui.widget.TextFieldWidget` | `client.gui.components.EditBox` |

## Etape

- [x] **Etapa 0 — fundatia.** `FlowCity.java`, `module/`, `net/`, `content/` (iteme + tab creativ),
  `client/ClientInit|ClientBridge|ClientHooks`. Erau deja aproape identice: `module/` si `net/`
  difereau doar prin importuri.
- [x] **Etapa 1 — UI comun.** `client/ui/`: `FTheme`, `FUi` (design system global: paleta, spatiere
  4/8/12/16, iconite vectoriale, butoane/file/comutatoare/scroll/stari goale), `Gfx`, `HudLayout`,
  `HudEditorScreen`, plus alinierea celor existente (`Ui`, `Theme`, `FlowScreen`, `Wrap`, `Xmas`,
  `FlowSounds`). **API-ul lor public e identic pe ambele versiuni** — asa portarea ecranelor ramane
  mecanica. Singura diferenta: parametrul `GuiGraphics g` vine din vanilla, nu din `compat`.
- [x] **Etapa 2 — `gps/`** (24 fisiere, ~11 000 linii). Harta, minimap, rutare pe drumuri,
  waypoint-uri, editor de harta si de zone, import de waypoint-uri. `HudLayout.register` pentru
  `minimap` si `gps` se face din `GpsClient.register()`.
- [x] **Etapa 3 — `fjobs/`** (15 fisiere, ~4 200 linii), inclusiv `elec/`.
  `HudLayout.register("fjobs", …)` din `FJobsClient.register()`.
- [x] **Etapa 3b — `hype/`** (6 fisiere) si `notify/` (5). `HypeClient.register()` leaga hook-ul
  `MiniMapRenderer.statusStrip` si inregistreaza `hud`, `toasts` si `status`.
- [x] **Etapa 4 — `cars/`** (21 vs 11 fisiere: lipsesc `FuelScreen`, `GarageAdmin*`, `Mecanic*`,
  `TuningScreen`, `Sk`, `VehicleParts`, `CarKeyItem`, `FuelPumps`) si `phone/` (5 vs 3 fisiere:
  telefonul nou cu App Store, SIM, Dynamic Island).
- [x] **Etapa 5 — restul**: `bank/`, `battlepass/`, `beginner/`, `docs/`, `fines/`, `guns/`,
  `houses/`, `hudeditor/`, `inventory/`, `pay/`, `politie/`, `shop/`, `taxi/`, plus aducerea la zi
  a celor ramase in urma (`news/` 7 vs 4, `menu/` 7 vs 6, `mdt/`, `wheel/`, `update/`, `voice/`).

Ordinea conteaza: `gps/` si `fjobs/` sunt folosite de alte pachete (waypoint automat la job,
navigare din telefon), iar `cars/` depinde de `Sk` din `cars/client`.

**Stare:** portare completa (etapele 0-5). `mod/` are ~50 500 de linii; CI verde pe ambele versiuni. Netestat in joc. `mod/` mai pastreaza vechiul `maps/` si blocurile GPS (nefolosite de `gps/`).

### Diferente gasite la etapele 4-5

| Eroare | Rezolvare |
|---|---|
| `level has private access` / `inventory has private access` | `p.level()`, `p.getInventory()`; `ServerPlayer` -> `serverLevel()` pentru `ServerLevel` |
| `Entity.remove()` | `discard()` |
| `SavedData(NAME)`, `computeIfAbsent(factory, NAME)` | fabrica statica `load(CompoundTag)` + `computeIfAbsent(load, new, NAME)`; `Constants.NBT.TAG_COMPOUND` -> `Tag.TAG_COMPOUND` |
| `sendSuccess(Component, b)` | `() -> Component` |
| `performCommand(source, cmd)` | `performPrefixedCommand` |
| `getProfileCache().get(id)` | `Optional<GameProfile>` |
| `Biome` | `Holder<Biome>.value()`, `getPrecipitationAt(pos)`, `getBaseTemperature()` (fara `getDownfall`) |
| `Vector3f.XP.rotationDegrees` | `com.mojang.math.Axis.XP.rotationDegrees` |
| `ContainerScreen` | `renderBg/render/renderLabels` iau `GuiGraphics`; `renderEntityInInventoryFollowsMouse(g, ...)`; `renderStatic(..., pose, buf, null, 0)` |
| `RenderNameTagEvent` | `getPoseStack()`, `getMultiBufferSource()`, `getPackedLight()` |
| `ConnectScreen` | `ConnectScreen.startConnecting(screen, mc, ServerAddress.parseString(ip), data, false)` |
| lista de moduri | `ConfigScreenHandler.getScreenFactoryFor(modInfo)`, logo prin `getOwningFile().getFile().findResource(..)` |
| `voice/` | NU se trece prin unealta: evenimentele `voicechat` isi pastreaza `getPlayer()` |


**Regresie acceptata la etapa 3b:** vechiul `JobsScreen` de pe 1.20.1 avea un buton „Arata pe
harta" care mergea prin `maps/` (harta veche). Varianta din 1.16.5 nu-l mai are, fiindca navigarea
s-a mutat in `gps/` (butoanele „Navigheaza" din panoul de joburi). Daca butonul e dorit inapoi, se
leaga la `GpsNav`, nu la `maps`.

## Dreptunghiurile implicite de HUD

`HudLayout.defaults()` dadea pe 1.16.5 direct in `gps`, `hype` si `cars`. Pe 1.20.1 ar fi insemnat
ca pachetele neportate tin build-ul rosu, deci acolo exista un **registru**:

```java
// din ClientInit-ul pachetului tau, cand il portezi
HudLayout.register("minimap", (sw, sh) -> MiniMapRenderer.defaults(sw, sh));
```

Nu modifica `HudLayout` ca sa adaugi un element — inregistreaza-l. Asa doua etape care merg in
paralel nu se calca.

## Reguli de lucru

1. **`mod/` trebuie sa compileze dupa fiecare commit.** Daca un ecran vechi din `mod/` foloseste
   API-ul vechi al UI-ului comun, adapteaza-l — nu lasa build-ul rosu.
2. **Nu sterge continutul care exista doar pe 1.20.1**: `content/GpsBlock`, `GpsDirBlock`,
   `GpsEvents`, `GpsNetwork`, `GpsWrench`, `CityMapScanner`, `MapItem`, pachetul `maps/`.
   Sunt o varianta mai veche de harta, inca folosita acolo.
3. **Resursele se copiaza odata cu codul**: `assets/flowcitycore/textures/item` (75 pe 1.16.5 fata
   de 24 pe 1.20.1), `models/item`, `lang/ro_ro.json` si `en_us.json`, `font/` (Inter, licenta OFL).
4. **Compilarea se face in CI** („Build FlowCity"); local nu merge, ForgeGradle are nevoie de retea.
   Defecte de infrastructura, nu de cod: `setup-java` cu „not in gzip format" si Maven Central cu
   „429 Too Many Requests" (pica `FlowCityRestart`). In ambele cazuri: asteapta ~90 s si
   `gh run rerun --failed`.
5. Nimic nu e testat in joc. CI dovedeste doar ca se compileaza.
