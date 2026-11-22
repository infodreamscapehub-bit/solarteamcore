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
| `shop-data.yml` | Generat: magazinele editate din joc (butonul EDITARE din magazin); suprascriu magazinul cu acelasi id din `shop.yml` |
| `permits.yml` | Permise: port-arma, vanatoare, licenta de afaceri (pret, descriere, cine le elibereaza, comenzile LuckPerms) |
| `lp-setup.txt` | Comenzile LuckPerms rulate de `/lpsetup` (grupuri small caps + gradient birdflop) |
| `plugin.yml` | Doar in jar (nume, versiune, softdepend) |

Fisiere de date generate: `shop-data.yml`, `bank-data.yml`, `houses-data.yml`, `docs-data.yml`, `politie-data.yml`, `taxi-data.yml`, `permits-data.yml`.

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

## Editarea magazinului din joc (flowcity.shop.admin)

- Cine are `flowcity.shop.admin` (implicit op-ii) vede in orice magazin un buton **EDITARE**. In modul de editare: pe fiecare card apar **EDIT** si **STERGE** (cu confirmare), cardul **ADAUGA ARTICOL**, tab-ul **+ CATEGORIE**, iar jos **REDENUMESTE** / **STERGE CATEGORIA**.
- Formularul articolului: nume, item (buton **DIN MANA** ia itemul pe care il tii in mana, ex. `minecraft:bread` sau `tac:ak47`), pret (> 0), cantitate (1-999), descriere, permise necesare (butoane pornit/oprit, din `permits.yml`) si, doar pentru op (sau `flowcity.shop.commands`), comenzi de consola separate prin `;` (`%player%`, `%amount%`).
- Serverul re-verifica permisiunea la fiecare operatie, valideaza datele (itemul trebuie sa existe pe server, daca nu are comenzi), apoi salveaza si retrimite lista tuturor celor care au magazinul deschis. Fiecare modificare apare in consola: `[shop] <jucator> (<magazin>): ...`.
- Modificarile se salveaza in `shop-data.yml`, care **suprascrie magazinul cu acelasi id** din `shop.yml`; `shop.yml` ramane neatins (cu comentarii). Ca sa revii la varianta din `shop.yml`, sterge magazinul din `shop-data.yml` si da `/shop reload`.
- Limite: 8 categorii pe magazin, 200 articole pe categorie, 4 permise necesare, 5 comenzi pe articol.

## GPS (harta + navigare)

- Jucatorii: `/gps` sau tasta **M** (schimbabila in Controls > FlowCity) sau `.gps` in chat deschid harta pe tot ecranul: panou LOCATII (cautare, filtre pe categorii, favorite ★), zone RP legale/ilegale, zoom (rotita), drag, click pe marcaj = selectare, **SETEAZA GPS** / **ANULEAZA GPS**. Click dreapta pe harta = punct personalizat. `/gps cancel` anuleaza navigarea.
- Navigarea este **directa** (sageata + distanta + ETA in HUD, fascicul la destinatie), nu ruta pe strazi. Cardul din HUD (element "Navigare GPS") se muta in editorul de HUD.
- Harta se deseneaza din lume pe client (chunk-urile incarcate in jurul jucatorului) si se salveaza local (`flowcity/gpsmap/` in folderul jocului), deci jucatorul vede doar ce a explorat sau a avut aproape.
- `gps.yml`: setari, `groups` (filtrele din panou), `categories`, `locations`, `zones` (poligon `points: [[x,z],...]` sau dreptunghi `x1/z1/x2/z2`, `type: LEGAL|ILEGAL`). Exemplele sunt dezactivate (`enabled: false`).
- Comenzi staff (`flowcity.gps.admin`, implicit op): `/gps lista [CAT]`, `/gps adauga <CAT> <nume>` (la pozitia ta), `/gps muta <id>`, `/gps sterge <id>`, `/gps activeaza|dezactiveaza <id>`, `/gps set <jucator> <x> <z> [nume]`, `/gps reload`. Modificarile din comenzi se salveaza in `gps-data.yml` (peste `gps.yml`, comentariile din `gps.yml` raman).
- Garajele si benzinariile create cu `/garaj` si `/benzinarie` apar automat pe harta (categoriile GARAJ si BENZINARIE), fara sa fie adaugate in `gps.yml`.

## FlowCity Jobs (Job Center) - fjobs.yml
- Se deschide cu `/fjobs`, tasta **J** (configurabila in Controls > FlowCity) sau aplicatia Joburi din telefon. Pentru NPC: `/npc command add fjobs open <p> -c` (ruleaza din consola, ca la magazin).
- Toata logica (angajare, tura, plata, XP, niveluri) ruleaza pe server. Clientul trimite doar intentii.
- `fjobs.yml` = setari + cele 25 de joburi (`activity`: `rules` = evenimente Bukkit cu plata per material, `delivery` = ridicare/livrare, `route` = checkpoint-uri, `external` = alt sistem, `none` = in pregatire).
- Status calculat automat: FUNCTIONAL, SISTEM EXTERN, IN PREGATIRE. Joburile de tip `delivery`/`route` devin functionale cand adminul seteaza punctele (Job Center > job > ADMINISTRARE > +RIDICARE / +LIVRARE / +CHECKPOINT, stand in locul dorit).
- Modificarile din GUI (flowcity.fjobs.admin) se salveaza in `fjobs-admin.yml` si suprascriu `fjobs.yml`; progresul jucatorilor e in `fjobs-data.yml` (scriere atomica + `.bak`).
- Joburile speciale (taxi, medic, politist) cer permisiunea facțiunii (`flowcity.taxi.driver`, `flowcity.ems.officer`, `flowcity.politie.officer`); instructorii si benzinaria cer permisiuni proprii (`flowcity.fjobs.instructor.auto|zbor`, `flowcity.fjobs.benzinarie`) si sunt "in pregatire".
- Motorul vechi din `hype.yml` (`jobs.*`) e oprit implicit (`jobs.enabled: false`) ca sa nu se plateasca de doua ori; HUD-ul arata jobul/nivelul/XP din FJobs. Progresul vechi NU se migreaza.
- Permisiuni: `flowcity.fjobs.use` (implicit toti), `flowcity.fjobs.admin` (OP), `flowcity.fjobs.premium` (+ `settings.premium-enabled: true`).

### Joburi civile cu gameplay propriu (`activity: custom`, `kind: ...`)
Cele 13 joburi civile (electrician, window_washer, garbage, courier, taxi, bus_driver, mechanic, fisher, farmer, miner, lumberjack, builder, postman) au fiecare o activitate proprie (`plugin/.../fjobs/act/*Activity.java`), cu panou, HUD si mini-jocuri. Nu mai folosesc `rules`/`delivery`/`route`.
- **Cum devine functional un job:** adminul seteaza punctele (Job Center > job > ADMINISTRARE): stai in joc unde vrei punctul, alegi *Grup/Linie/Ruta nr.* si apesi **+ ADAUGA AICI** pe tipul de punct (tablou, platforma, geam, container, depozit, statie, box, piata, lot, zona...). Numele se genereaza (ex. `Cladire 2 - geam 3`, `Linia 1 - statia 4`); tiparele se pot schimba cu `station-names.<tip>: "Text {g} {n}"` in `fjobs.yml`. Fara punctele cerute jobul ramane **IN PREGATIRE** si motivul apare in Job Center. Punctele se salveaza in `fjobs-admin.yml` (`jobs.<id>.stations.<tip>`). Exemplele din `fjobs.yml` sunt comentate (dezactivate).
- **Prefix = grup:** la `window_washer`, `garbage`, `bus_driver`, `postman` punctele cu acelasi prefix inainte de ` - ` formeaza o cladire / ruta / linie / strada.
- **Panou job:** buton *PANOU JOB* in fereastra jobului din Job Center sau tasta "Deschide panoul jobului" (Controls > FlowCity, nelegata implicit). Contine obiectivele, statisticile, dificultatea (usor/mediu/greu, deblocata dupa nivelul jobului), butoane de actiune si tabelul de recompense. Cardul din HUD (`fjobs`) se muta din editorul de HUD.
- **Configurare:** fiecare job are in `fjobs.yml` sectiunea `levels:` (multiplicatori `pay`/`xp`, `size`, `seconds` pe mini-joc, `attempts`, `min-level`) plus parametrii proprii (plati, raze, timpi, tabele de peste/minereuri/culturi/defecte). `est-pay`, nivelul minim, locurile si multiplicatorul se pot schimba si din GUI.
- **Mini-jocuri** (`wires`, `pick`, `sort`, `seq`): serverul genereaza problema si solutia; clientul doar o afiseaza. Serverul verifica timpul minim plauzibil, numarul de incercari si limita de timp. *Limita:* problema trebuie trimisa clientului ca sa fie afisata, deci un client modificat o poate rezolva automat dupa timpul minim; plata ramane limitata de raza/viteza/cooldown-uri si de `limit-per-minute`.
- **Anti-abuz:** plata si XP trec doar prin server (in tura, job activ); blocurile puse de jucatori nu se platesc (`placed-ttl-minutes`); resursele (miner, lemn, fermier) se pot preda doar in limita a ce s-a obtinut legal in zone (alocatie persistenta); colete/scrisori/piese sunt iteme marcate si validate pe server; viteza maxima la livrari/taxi.
- **Limite cunoscute:** (1) taxi/autobuz nu pot detecta sigur masinile din Vehicle Mod ca vehicule Bukkit: `require-vehicle` e oprit implicit; masuratoarea se face pe deplasarea jucatorului. (2) Jobul taxi nou e separat de sistemul de taxi din telefon (acela ramane extern si necesita `flowcity.taxi.driver`; la fel si noul job). (3) `mechanic` e un sistem de comenzi de service propriu, nu foloseste ecranele de tuning/mecanic ale masinilor. (4) Reparatiile de constructor apar doar daca santierul are blocuri crapate construite de admin. (5) Animatiile sunt particule/sunete si schimbari de geam vazute doar de jucator; nu exista animatii de model.
