# FlowCityBridge - fisiere de configurare

Toate fisierele `.yml` se afla in `plugins/FlowCityBridge/` si se creeaza la prima pornire (valorile lipsa se iau din jar).
Datele jocului se salveaza separat in `*-data.yml` si in baza de date SQLite a pluginului: nu le edita cat ruleaza serverul.

| Fisier | Ce controleaza |
|---|---|
| `config.yml` | Setari generale: numele serverului, TAB, canal mod, actualizari |
| `hype.yml` | HUD (stiluri, rank LuckPerms, prefix/sufix), ATM, joburi, roata, livrari, documente, amenzi (`mdt:`) |
| `help.yml` | `/help`: categorii, comenzi, permisiuni, culori gradient, **small caps** (`smallcaps: true/false`) |
| `payday.yml` | PayDay: interval, AFK, salariu de baza, taxa, salarii pe grup (`flowcity.payday.<grup>`) |
| `bank.yml` | Banca: credite, dobanzi, economii |
| `mecanic.yml` | Mecanic NPC: preturi reparatie, ITP, RCA |
| `houses.yml` | Case si chirii: pret de vanzare, limita de case, chirie |
| `politie.yml` | Politie: taxe (permis, port-arma, cazier), conditii, roluri de angajare, permisiune ofiter |
| `primarie.yml` | Primarie: taxe buletin, duplicat, reinnoire, varsta minima |
| `taxi.yml` | Taxi: tarife (pornire, pe km, asteptare), comision, cooldown, licenta sofer |
| `shop.yml` | E-shop-urile deschise de NPC (arme, magazine, backpackuri, permise); articolele pot cere permis (`requires: port_arma`) |
| `permits.yml` | Permise: port-arma, vanatoare, licenta de afaceri (pret, descriere, cine le elibereaza, comenzile LuckPerms) |
| `lp-setup.txt` | Comenzile LuckPerms rulate de `/lpsetup` (grupuri small caps + gradient birdflop) |
| `plugin.yml` | Doar in jar (nume, versiune, softdepend) |

Fisiere de date generate: `bank-data.yml`, `houses-data.yml`, `docs-data.yml`, `politie-data.yml`, `taxi-data.yml`, `permits-data.yml`.

## Reincarcare

- `/help reload` reincarca `help.yml` (permisiunea `flowcity.help.admin`).
- `/payday reload` reincarca `payday.yml` (`flowcity.payday.admin`); `/shop reload` reincarca `shop.yml`; `/permis reload` reincarca `permits.yml` (`flowcity.permits.admin`; dupa el da si `/shop reload`).
- `/hype reload` reincarca `hype.yml` (`flowcity.hype.admin`); `/primarie reload` reincarca `primarie.yml` (`flowcity.hype.admin`).
- Pentru restul fisierelor: restart sau `/flowcity` (verificare/actualizare) urmat de restart.

## Small caps

`smallcaps: true` din `help.yml` este comutatorul global: titlurile din `/help`, titlurile notificarilor si numele rangurilor
de rezerva din HUD apar in ꜱᴍᴀʟʟ ᴄᴀᴘꜱ. Codurile de culoare (`&x`, `&#RRGGBB`, gradient), placeholderele (`%..%`, `{..}`),
comenzile `/slash`, cifrele si diacriticele raman neschimbate. Pune `false` ca sa il opresti peste tot.

## Permise si magazine

- Un NPC Citizens deschide un magazin: `/npc command add shop open <p> <magazin> -c` (acelasi format ca la dealer/tuning). Magazine: `arme`, `magazine`, `backpackuri`, `permise`.
- `permits.yml` defineste permisele (`port_arma`, `permis_vanatoare`, `licenta_afaceri`): pret, descriere, cine il elibereaza. Se editeaza liber; id-urile noi se pot folosi in `shop.yml`.
- Se cumpara din magazinul `permise` (intrari `{permit: port_arma}`) sau le da politia: `/permis da <jucator> <id>`, `/permis retrage <jucator> <id>`, `/permis lista [jucator]` (permisiune `flowcity.politie.officer`). Jucatorul vede `/permise`.
- La acordare se ruleaza comanda LuckPerms din `permits.yml` (`lp user %player% permission set flowcity.permit.<id> true`); la retragere se scoate. Evidenta e in `permits-data.yml`.
- Un articol din `shop.yml` cere permis cu `requires: port_arma` (sau o lista). Verificarea e facuta pe server; in meniu articolul apare gri, cu lacat si textul "NECESITA PERMIS: ...".
