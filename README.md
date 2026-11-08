# FlowCity - SolarStudios (faza 1: bridge + eAdmin)

Portul SolarTeamCore 1.20.1 -> Forge 1.16.5, rebrand **FlowCity**.
Faza 1 contine infrastructura (bridge Mohist/Arclight) si **eAdmin nou, cu panou in joc**.

```
flowcity/
  plugin/   FlowCityBridge  - plugin Bukkit/Spigot 1.16.5 (merge pe Mohist/Arclight)
  mod/      FlowCityCore    - mod Forge 1.16.5 (CLIENT) cu panoul eAdmin
```

## Build fara sa instalezi nimic (GitHub Actions)
Urca continutul acestui folder intr-un repo GitHub (privat e ok). La fiecare push, tab-ul **Actions** compileaza automat
ambele jaruri; le iei din **Artifacts** (`FlowCityCore-mod`, `FlowCityBridge-plugin`). Nu e nevoie de JDK/Gradle local.

## Build local
- **mod**: JDK 8, `cd mod && ./gradlew build` -> `build/libs/FlowCityCore-1.16.5-1.0.0.jar`
  (ForgeGradle 5.1, mappings MCP `snapshot 20210309-1.16.5`, Forge 36.2.39). Pentru IDE: `./gradlew genIntellijRuns`.
- **plugin**: JDK 8-17, `cd plugin && ./gradlew build` -> `build/libs/FlowCityBridge-1.0.0.jar`.
- Wrapper-ul foloseste Gradle 7.6.4 (cerut de ForgeGradle 5).

## Instalare
1. `FlowCityBridge-1.0.0.jar` in `plugins/` pe serverul Mohist/Arclight. (Vault e optional: da soldul in profil.)
2. `FlowCityCore-1.16.5-1.0.0.jar` in `mods/` la **clienti**. Modul **nu** se pune pe server.
3. Da staff-ului permisiunile (LuckPerms etc.):
   `flowcity.eadmin` (acces + vizualizare), `.sanction`, `.notes`, `.tp`, `.ip`; `flowcity.eadmin.immune` = nu poate fi sanctionat de non-OP.
4. In joc: tasta **F9** (se schimba in Controls > FlowCity) sau `/eadmin`.

## Ce face eAdmin
- **Panou in joc**: lista jucatorilor (online primii, cautare, paginare, marcaj BAN/MUTE), profil, loguri, sanctiuni, note.
- **Profil**: status, prima/ultima conectare, timp jucat, sold (Vault), IP (doar cu `.ip`), contoare WARN/MUTE/KICK/BAN, flag-uri automate, TP la jucator / aduce-l la tine.
- **Loguri** (cu paginare): chat, comenzi, conexiuni (IP + coordonate), creativ (spawn/gamemode), containere (PUT/TAKE), tranzactii.
- **Sanctiuni**: WARN, MUTE, KICK, BAN cu durata (15m/1h/1d/7d/30d/permanent), motiv, revocare, istoric. Mute-ul blocheaza chatul si comenzile din `muted-blocked-commands`; ban-ul se verifica la login.
- **Note staff** per jucator.
- **Flag-uri automate**: cont multiplu pe acelasi IP, spam de comenzi, obiecte blacklist din creativ, tranzactii peste prag. Staff-ul online primeste alerta in chat.
- **Comenzi** (si din consola): `/eadmin [open|info|warn|mute|unmute|kick|ban|unban|note] <jucator> [durata] [motiv]`. Durate: `30m 2h 7d perm`.
- Securitate: serverul verifica permisiunea la **fiecare** pachet; clientul nu e de incredere.

## Bridge (cum comunica)
Canal `flowcity:bridge`, bytes in format PacketBuffer (VarInt + UTF). Pe client: `EventNetworkChannel`; pe server:
Plugin Messaging Bukkit (Mohist/Arclight trimit payload-urile necunoscute catre Bukkit). Clientul face handshake (HELLO)
dupa intrare, deci `/eadmin` stie daca jucatorul are modul. Protocolul e in `Op.java` (plugin) si `Bridge.java` (mod) - trebuie sa ramana identice.

## API pentru alte plugin-uri (banca, ATM, magazine)
```java
FlowCityBridge.getInstance().service().logTransaction(uuid, "DEPOSIT", 1500.0, "ATM Centru", "world 10 64 20");
FlowCityBridge.getInstance().service().flag(uuid, "HIGH", "DUPE_SUSPECT", "detalii");
```

## De verificat la prima rulare (nu am putut compila aici)
Mediul meu nu poate descarca Forge/Spigot, deci codul e verificat doar sintactic, plus teste reale pentru codec-ul Wire si pentru SQL (SQLite).
1. **Compilarea modului**: daca `gradlew build` da erori de tip "cannot find symbol" pe metode de GUI, trimite-mi lista; sunt nume MCP 1.16.5 si se corecteaza rapid.
2. **Canalul pe Mohist/Arclight**: dupa intrare, in consola nu trebuie erori; `/eadmin` ca jucator cu mod trebuie sa deschida panoul. Daca nu, spune-mi versiunea exacta de Mohist/Arclight.
3. Driverul SQLite e cel din server (Spigot il include).

## Urmeaza
Faza 2a (livrata): **Hype-lite** = HUD, ATM, joburi, sete (`/atm`, `/job`, `/sete`, `/hud`, `/hype atm set|remove|list`, `/hype reload`; config `hype.yml`; permisiuni `flowcity.hype.atm|job|admin`). ATM-ul si joburile merg si fara mod (text in chat); HUD-ul si ecranele necesita modul pe client.
Amanate (le refacem dupa): CNN, proprietati, roata, documente (buletin/permis), meniu principal, ClearLagg, eMaps, ePhone.
Faza 3: eMaps. Faza 4: ePhone. Jarul original nu contine sursa si lipsesc clase (core, joburi), deci acestea se reconstruiesc.
