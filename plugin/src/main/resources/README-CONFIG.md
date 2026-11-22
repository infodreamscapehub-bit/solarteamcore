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
- Navigarea: **fara retea de drumuri** salvata pe server, GPS-ul e doar **directie directa** (sageata + distanta + ETA, fascicul la destinatie) si cardul scrie "rețea de drumuri lipsă". **Cu retea**, traseul urmeaza strazile (vezi "GPS pe drumuri" mai jos). Cardul din HUD (element "Navigare GPS") se muta in editorul de HUD.
- Harta se deseneaza din lume pe client (chunk-urile incarcate in jurul jucatorului) si se salveaza local (`flowcity/gpsmap/` in folderul jocului), deci jucatorul vede doar ce a explorat sau a avut aproape.
- `gps.yml`: setari, `groups` (filtrele din panou), `categories`, `locations`, `zones` (poligon `points: [[x,z],...]` sau dreptunghi `x1/z1/x2/z2`, `type: LEGAL|ILEGAL`). Exemplele sunt dezactivate (`enabled: false`).
- Comenzi staff (`flowcity.gps.admin`, implicit op): `/gps lista [CAT]`, `/gps adauga <CAT> <nume>` (la pozitia ta), `/gps muta <id>`, `/gps sterge <id>`, `/gps activeaza|dezactiveaza <id>`, `/gps set <jucator> <x> <z> [nume]`, `/gps reload`. Modificarile din comenzi se salveaza in `gps-data.yml` (peste `gps.yml`, comentariile din `gps.yml` raman).
### GPS pe drumuri (traseu pe strazi)
- Reteaua de drumuri se **deseneaza de un admin** (`flowcity.gps.admin`) in joc: `/gps` > EDITOR HARTA > fila **DRUMURI**. Se salveaza in `plugins/FlowCityBridge/gps/roads.json` (scriere atomica, validare la server, backup automat in `gps/backups/`). **Pana nu desenezi reteaua, GPS-ul ramane in linie dreapta.**
- Unelte: DESENEAZA (click = nod nou legat de precedentul; click pe un drum il imparte; click dreapta termina lantul), MUTA (tragi nodurile), STERGE (nod sau segment; click dreapta sterge si el), SENS UNIC (click = sens unic pe tot drumul, Shift+click inverseaza), NUME STRADA (scrii numele, click pe segment: il pune pe tot drumul pana la intersectie), CONECTEAZA LOC. (leaga o locatie de cel mai apropiat drum; buton si pentru toate locatiile fara drum aproape). Ctrl+Z / Ctrl+Y = undo/redo. Modificarile sunt o **ciorna locala** pana apesi SALVEAZA REȚEAUA PE SERVER (te avertizeaza daca reteaua are parti separate).
- VERIFICA REȚEAUA: numara partile conexe, nodurile izolate si locatiile fara drum la 40 de blocuri. ANULEAZA SALVAREA readuce versiunea salvata anterior; STERGE TOT goleste reteaua (cu backup).
- **Scanare automata (optionala)**: alegi tipurile de bloc ale drumului (BLOC DE SUB MINE / BLOC ȚINTIT: ex. beton negru + linii albe), pasul de esantionare (2-8, drumurile mai inguste decat pasul se pot pierde) si zona (ce se vede sau 2 colturi). Serverul citeste doar chunk-urile **deja incarcate** (cel mult ~1200 de puncte/tick, zona max ~250.000 de puncte), subtiaza drumul si trimite o **previzualizare** (verde); o accepti in ciorna, o corectezi si abia apoi salvezi. Doar Overworld, doar blocuri vanilla. Copacii/acoperisurile peste drum pot ascunde bucati.
- Jucatorii primesc reteaua la intrare si dupa fiecare salvare. Traseul: A* pe retea (respecta sensurile unice), recalculat cand iesi de pe drum (>20 blocuri, ~1,5 s), cu viraje + nume de strada in HUD (indicatie sus in centru + card), panglica pe sol in lume, linia pe harta GPS si minimap, distanta si ETA pe drum, anunturi sonore la 300/110/35 m (stratul/comutatorul "Sunete la viraje" din STRATURI). Stratul "Rețea de drumuri" arata reteaua pe harta (implicit ascuns). Daca punctul de start e la >160 de blocuri de un drum, sau destinatia la >260, sau nu sunt legate intre ele, GPS-ul cade pe linie dreapta si cardul spune de ce.
- Limite: max 30.000 noduri / 60.000 segmente, rutarea nu stie de benzi, semafoare sau trafic.
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

### Electrician (job complet) - `jobs.electrician` in fjobs.yml
- **Flux:** panoul jobului (tasta "Deschide panoul jobului" sau Job Center > PANOU JOB) are INCEPE TURA, INTERVENTII DISPONIBILE, ECHIPAMENT, STATISTICI, TERMINA TURA (si ADMINISTRARE pentru admini). In tura apar oferte de interventii (max `max-offers`, expira dupa `offer-ttl-seconds`). Accepti una (ai nevoie de *trusa*), mergi la locatie (GPS) si dai **click dreapta** pe punct (raza `interact-radius`): se deschide panoul electric cu schema circuitului, tensiune, indicator de functionare si etapele. Fiecare etapa e un mini-joc: **circuit** (tragi firele), **fuse** (multimetru ceruit serverului -> amperaj -> verificare), **seq** (secventa progresiva), **calib** (tii indicatorul in interval pe osciloscop).
- **Tipuri** (`types.<id>`): `panou, retea, sigurante, cablu, generator, transformator, instalatie, iluminat`. Fiecare are `reward`, `xp`, `seconds` (timp estimat), `min-rank`, `min-diff`/`max-diff` (1-5), `volt`, `stages` (ordinea etapelor din `circuit, fuse, seq, calib`), `needs` (echipament necesar/consumat, ex. `{cablu: 2}`, `{protectie: 1}`) si `enabled`. La dificultate mai mare plata creste ~35% si XP ~30% pe treapta; plata finala = reward x (1 + bonus rang) x (0.55 + 0.6 x calitate), apoi bonusul de nivel si multiplicatorul jobului (prin `FJobsApi.pay`).
- **Niveluri** (`ranks.<id>`): Ucenic, Electrician Junior, Electrician, Electrician Senior, Specialist Electric, Inginer Electric. Legate de nivelul jobului (`min-level`); `max-diff` deblocheaza interventii mai grele, `pay-bonus` creste plata.
- **Echipament** (`equipment.<id>`): `trusa` (obligatorie), `multimetru` (masoara siguranta defecta; fara el ghicesti), `cleste` (+1 greseala la circuit), `cablu` si `siguranta` (consumabile), `protectie` (fara soc la esec; obligatorie de la `protection-from-diff` si la transformator). Sunt iteme reale cu eticheta (PDC `fj_elec`); pret, `pack`, `max`, `min-rank`, `material` din config sau din panou (admin). La prima tura se da kit de start (`starter`). Banii se retrag/dau prin Vault ca la celelalte joburi.
- **Puncte:** Administrare (in panoul jobului, tab ADMINISTRARE, sau Job Center > Electrician > puncte): fiecare tip are punctele lui (`+ AICI` = locatia ta). Un tip fara puncte proprii foloseste punctele generale `panel`. Fara niciun punct jobul e IN PREGATIRE. Modificarile de pret/recompensa/ON-OFF din panou se salveaza in `fjobs-admin.yml` (`jobs.electrician.conf.*`) si suprascriu `fjobs.yml`.
- **Persistenta:** istoric (ultimele 40) si statistici in `plugins/FlowCityBridge/fjobs-electrician/<uuid>.json`.
- **Securitate:** serverul genereaza solutiile, tine etapa curenta, greselile, timpii minimi si distanta fata de punct; clientul trimite doar intentii (`job_op`/`el_g`). Calibrarea: serverul reface curba tinta si respinge urme prea perfecte. Limita: datele puzzle-ului ajung la client ca sa fie afisate, deci un client special facut le-ar putea rezolva automat; timpii minimi si greselile limitate opresc rezolvarea instantanee.
- **Limite:** ofertele sunt per jucator (doi electricieni pot primi aceeasi locatie); socul electric nu omoara niciodata (lasa minim 1 viata).

### Joburi civile cu gameplay propriu (`activity: custom`, `kind: ...`)
Cele 13 joburi civile (electrician, window_washer, garbage, courier, taxi, bus_driver, mechanic, fisher, farmer, miner, lumberjack, builder, postman) au fiecare o activitate proprie (`plugin/.../fjobs/act/*Activity.java`), cu panou, HUD si mini-jocuri. Nu mai folosesc `rules`/`delivery`/`route`.
- **Cum devine functional un job:** adminul seteaza punctele (Job Center > job > ADMINISTRARE): stai in joc unde vrei punctul, alegi *Grup/Linie/Ruta nr.* si apesi **+ ADAUGA AICI** pe tipul de punct (tablou, platforma, geam, container, depozit, statie, box, piata, lot, zona...). Numele se genereaza (ex. `Cladire 2 - geam 3`, `Linia 1 - statia 4`); tiparele se pot schimba cu `station-names.<tip>: "Text {g} {n}"` in `fjobs.yml`. Fara punctele cerute jobul ramane **IN PREGATIRE** si motivul apare in Job Center. Punctele se salveaza in `fjobs-admin.yml` (`jobs.<id>.stations.<tip>`). Exemplele din `fjobs.yml` sunt comentate (dezactivate).
- **Prefix = grup:** la `window_washer`, `garbage`, `bus_driver`, `postman` punctele cu acelasi prefix inainte de ` - ` formeaza o cladire / ruta / linie / strada.
- **Panou job:** buton *PANOU JOB* in fereastra jobului din Job Center sau tasta "Deschide panoul jobului" (Controls > FlowCity, nelegata implicit). Contine obiectivele, statisticile, dificultatea (usor/mediu/greu, deblocata dupa nivelul jobului), butoane de actiune si tabelul de recompense. Cardul din HUD (`fjobs`) se muta din editorul de HUD.
- **Configurare:** fiecare job are in `fjobs.yml` sectiunea `levels:` (multiplicatori `pay`/`xp`, `size`, `seconds` pe mini-joc, `attempts`, `min-level`) plus parametrii proprii (plati, raze, timpi, tabele de peste/minereuri/culturi/defecte). `est-pay`, nivelul minim, locurile si multiplicatorul se pot schimba si din GUI.
- **Mini-jocuri** (`wires`, `pick`, `sort`, `seq`): serverul genereaza problema si solutia; clientul doar o afiseaza. Serverul verifica timpul minim plauzibil, numarul de incercari si limita de timp. *Limita:* problema trebuie trimisa clientului ca sa fie afisata, deci un client modificat o poate rezolva automat dupa timpul minim; plata ramane limitata de raza/viteza/cooldown-uri si de `limit-per-minute`.
- **Anti-abuz:** plata si XP trec doar prin server (in tura, job activ); blocurile puse de jucatori nu se platesc (`placed-ttl-minutes`); resursele (miner, lemn, fermier) se pot preda doar in limita a ce s-a obtinut legal in zone (alocatie persistenta); colete/scrisori/piese sunt iteme marcate si validate pe server; viteza maxima la livrari/taxi.
- **Limite cunoscute:** (1) autobuzul nu detecteaza masinile din Vehicle Mod (`require-vehicle` oprit implicit, masuratoarea se face pe deplasarea jucatorului); taxiul le detecteaza (vezi mai jos). (2) Jobul taxi nou e separat de sistemul de taxi din telefon (acela ramane extern si necesita `flowcity.taxi.driver`; la fel si noul job). (3) `mechanic` e un sistem de comenzi de service propriu, nu foloseste ecranele de tuning/mecanic ale masinilor. (4) Reparatiile de constructor apar doar daca santierul are blocuri crapate construite de admin. (5) Animatiile sunt particule/sunete si schimbari de geam vazute doar de jucator; nu exista animatii de model.

### Echipament real pe joburi (iteme) - `jobs.<id>.gear` + `settings.gear`
Fiecare job civil (cu exceptia electricianului, care isi are sistemul propriu `equipment`) are in `fjobs.yml` sectiunea `gear:` cu unelte si consumabile **reale** din inventar, luate din *Panou job > ECHIPAMENT* (cu bani, sau gratis ca kit de start la prima tura).
- **Cheie item:** `name`, `desc`, `material` (baza vanilla), `price`, `pack` (bucati per cumparare), `max` (cate poti avea), `starter` (bucati gratuite la prima tura a jobului), `uses` (utilizari; 0 = fara uzura; scade la fiecare plata, itemul dispare cand se termina), `required` (fara el nu incepi tura; daca il pierzi, plata se inmulteste cu `settings.gear.missing-required-pay`), `consumable` + `consume: pay|mission` (se consuma la fiecare plata / la fiecare misiune), `soulbound` (nu poate fi aruncat, pus in containere, nu cade la moarte), `remove-on-end` + `refund` (sters la sfarsitul turei, cu returnare partiala), `bonus-pay` / `bonus-xp` (+% cat timp il ai; total plafonat de `settings.gear.max-bonus`), `min-level`, `enabled`, `glow`, `enchants`.
- **Securitate:** itemele au eticheta PDC pe server (`fj_gear`, `fj_gear_owner`, `fj_gear_uses`); redenumirea sau un item de acelasi material nu le imita. Cumpararea, consumul, uzura si bonusurile se calculeaza DOAR pe server, in plata (`FJobsModule.payOut`). Banii se retrag prin economia serverului.
- **Admin:** in pagina ECHIPAMENT (cont cu `flowcity.fjobs.admin`) ai butoane `-10 / +10` pret, `ACTIV/OPRIT` si `OBL` (obligatoriu); se salveaza in `fjobs-admin.yml` (`jobs.<id>.conf.gear.<item>.*`).
- **Joburile fara activitate proprie** (livrator, camionagiu, muncitor portuar, manipulant, pompier, salvamar, salubritate) primesc un panou minimal, doar cu pagina ECHIPAMENT.
- **Texturi:** itemele NU au texturi proprii (baza vanilla + nume colorat + lore + stralucire); iconitele din pagina sunt desenate vectorial.
- **Limite:** uneltele existente (tarnacop, topor, lanseta, galeata) pastreaza verificarile vechi pe material; unealta de echipament doar adauga cerinta/bonus/uzura. Uzura se scade la plata, nu la fiecare lovitura. Nu exista bonus pentru piesele purtate pe corp (se verifica doar prezenta in inventar).

### Taxi cu Vehicle Mod (`jobs.taxi`)
- **Detectie (server, Arclight 1.16.5):** `VehicleLink` ia vehiculul jucatorului (`getVehicle()`), apoi ii citeste clasa NMS prin `CraftEntity#getHandle()` si accepta daca in ierarhie apare un pachet din `vehicle-classes` (`com.mrcrayfish.vehicle.`; numele claselor din mod nu sunt remapate). Rezerva: namespace-ul tipului de entitate (`vehicle-namespaces`). Soferul = primul pasager (ca `getControllingPassenger()`).
- **Reguli (`vehicle-mod: true`):** tura incepe doar daca esti SOFERUL unui vehicul Vehicle Mod; comenzile se accepta, pasagerul se urca si cursa se termina doar ca sofer. Distanta se numara doar cu **acelasi vehicul** si doar pasi plauzibili (`max-speed`); teleportarile/vehiculul schimbat sunt respinse. Cu `vehicle-mod: false` se revine la comportamentul vechi (`require-vehicle`).
- **Pasager:** un alt jucator asezat in acelasi vehicul devine pasager real (`require-real-passenger: true` il cere). Daca coboara cat timp cursa e in desfasurare: dupa `leave-grace-seconds` plata se inmulteste cu `leave-penalty`, dupa `leave-abort-seconds` cursa se anuleaza fara plata. Fara pasager real, pasagerul e NPC virtual (fara verificare de coborare).
- **Plata:** `start-fare` + `per-100m` x distanta, `+clean-bonus` fara franari bruste, `+speed-bonus` la viteza medie >= `bonus-avg-speed`, bacsis/penalizare de timp ca inainte. Plata trece prin `FJobsApi.pay`.
- **Taximetru in HUD:** cardul jobului arata distanta, tariful, viteza curenta/maxima, "condus curat" si starea pasagerului. GPS: destinatia/ridicarea se trimit in navigatia GPS existenta.
- **Marker TAXI:** optional (`marker: true`); numele vehiculului devine `marker-text` pe durata turei si se reface la sfarsit (la un crash de server numele poate ramane).
- **Limite:** (1) neverificat in joc; detectia prin clasa depinde de pachetul real al Vehicle Mod 0.45.2. (2) Impactul se deduce din scaderea brusca de viteza, nu din daunele vehiculului. (3) Pasagerul NPC nu sta efectiv in vehicul. (4) Permisiunea `flowcity.taxi.driver` ramane necesara.
