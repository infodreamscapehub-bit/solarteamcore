# Statii radio personale FlowCity

**Server (FlowCityBridge, modul `radio`)** — logica in `radio/Stations.java` (fara Bukkit, 23 teste in `StationsTest`), reteaua/persistenta in `RadioStationsModule`, config in `radio.yml`, date in `radio-stations.json`.
**Client (mod116 + mod, pachet `radio`)** — `RadioScreen` (tasta `;` sau click dreapta pe radio), `RadioHud` (element mutabil "radio" in editorul de HUD).

## Cum merge
- Doar jucatorii cu un radio valid (item `radio` cu serial) creeaza/se conecteaza. Radio OFF = nu transmite si nu aude.
- Tipuri: PUBLIC, PRIVATE (doar pe invitatie), PASSWORD, GROUP (ca PRIVATE, pentru grupuri), DEPARTMENT (doar oficiale, din `radio.yml`).
- Roluri: OWNER, CO-OWNER, MEMBER. Owner: transfer, inchidere, promovare. Owner offline > grace → ownership trece automat la un CO-OWNER/membru, altfel statia se inchide.
- Parole: PBKDF2-HMAC-SHA256 + sare; blocare dupa 5 greseli / 5 min. Limite: statii/jucator, cooldown creare, cooldown/TTL invitatii (toate in `radio.yml`).
- Frecventele oficiale (Politie, EMS, Mecanici, Taxi, Public, Staff) sunt rezervate; acces din permisiunile/joburile canalului din `items.yml` sau `flowcity.radio.official.<cheie>` / `flowcity.radio.official.*`.
- Comanda `/statie` (aliasuri `/statii`, `/station`) deschide ecranul interactiv de stații (liste, CREEAZĂ RADIO, invitații, stația curentă). Serverul verifică să ai un radio valid la tine; fără radio primești un mesaj. Fără modul pe client rămâne radioul text (`/radio <mesaj>`). Nu există subcomenzi: totul se face din ecran.
- Admin: permisiunea `flowcity.radio.admin` → tab "Admin" (lista statii, inchidere fortata, limita statii/jucator); `/radiostatii reload`.

## Voce (Plasmo Voice) si ce NU e voce
- Addon propriu `FlowRadioAddon` (Plasmo Voice 2.1.9): linie `flowcity_radio` + activare push-to-talk "FlowCity Radio" (tasta se leaga in setarile Plasmo Voice). Audio primit de server e retransmis printr-o sursa broadcast doar catre cei acordati pe aceeasi statie.
- Fara Plasmo Voice pe server: totul merge cu **radio text** (`/radio <mesaj>` pe statia activa). Statusul apare in ecran si in `/radiostatii`.
- **Neverificat in joc:** incarcarea addonului pe Arclight/Mohist (Plasmo Voice ca mod Forge vs plugin), ecoul vorbitorului (primeste propria voce?), indicatorul "cine vorbeste".
