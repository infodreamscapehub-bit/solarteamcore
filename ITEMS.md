# Iteme functionale FlowCity (framework `FlowCityItemActions`)

Itemele de echipament au **functie reala, validata pe server**. Clientul (mod) afiseaza doar bara de progres, panourile si sunetele.

## Cum functioneaza
- **Emitere**: doar serverul (`/fcitem give <jucator> <id>`, panou admin `/fcitem`, magazin `shop.yml` -> `echipament`, battlepass/roata cu `fcitem give %player% <id>`). Fiecare item are **serial unic** (PDC + registru `plugins/FlowCityBridge/items/serials.tsv`).
- **Anti-duplicare / falsificare**: item fara serial, cu serial necunoscut sau revocat nu functioneaza; copiile (acelasi serial la doua stive/jucatori) sunt sterse si revocate, iar admin-ii (`flowcity.items.admin`) sunt notificati. Verificare la fiecare folosire si la 60 s.
- **Cine il poate folosi**: `permissions` (oricare) SAU `jobs` (jobul FJobs activ, in tura) din `items.yml`.
- **Actiuni cu durata**: se anuleaza daca te misti, schimbi itemul, iesi de pe server, mori sau tinta se departeaza/pleaca. Rezultatul e salvat si scris in `fc_item_log`.
- **Persistenta**: dosare, probe, sesiuni bodycam, poze, comenzi de piese, jurnal = SQLite; starea "la pamant", catuse, seriale = fisiere in `plugins/FlowCityBridge/items/`.

## Itemele
| Item | Ce face | Integrare |
|---|---|---|
| `catuse` | incatuseaza / escorteaza / elibereaza; restrictii cat esti incatusat; eliberare automata; alerta la delogare | jurnal, bodycam |
| `scanner_amprente` + `proba_amprenta` | colecteaza urme lasate pe usi/containere/vehicule (nu si cu manusi), ia amprenta persoanei, compara cu baza, scrie potrivirea in fisa MDT | dosar activ, MDT (`addItemRecord`), jurnal |
| `bodycam` | sesiune persistenta (start/stop, colegi in apropiere, marcaje automate la actiunile cu iteme); un coleg o verifica (raport coleg); se ataseaza dosarului | dosar, jurnal |
| `radio` | canale cu drepturi (public/politie/EMS/mecanici/taxi), `/radio <mesaj>`, apel de urgenta cu pozitia | permisiuni/joburi, jurnal |
| `geanta_medicala` | trateaza pansat, **stabilizeaza** pacientul la pamant, il **transporta** (sneak) si il trateaza complet (zona de spital optionala) | sistem "la pamant", alerte EMS |
| `defibrilator` | reporneste inima la stop cardiac; sansa scade cu timpul si cu socurile | sistem "la pamant" |
| `scanner_diagnoza` | citeste starea REALA a vehiculului din mod (caroserie, combustibil, ITP, RCA, motor, roti, km); comanda piese; ataseaza la dosar | `fccars diag`, economie (`Money`) |
| `trusa_reparatii` + `piesa_auto` | reface caroseria reala (Health) consumand o piesa auto; piesa se returneaza daca modul refuza | `fccars repair`, comenzi `fc_order` |
| `camera_foto` + `fotografie` | poza = inregistrare (cine, unde, cand, cine era in cadru) + item "fotografie"; se ataseaza dosarului sau se publica la CNN | NewsModule (`publishFromItem`), dosar |
| `taximetru` (`FLOWCITYCORE_TX_APARAT`) | arata tariful/distanta/timpul cursei REALE din Taxi; trimite tariful pasagerului; opreste cursa | TaxiModule (`meter*`) |
| `harta` (GPS) | deschide GPS-ul real (un singur sistem GPS) | GpsScreen |
| tableta MDT / admin / telefon / ATM | **neschimbate** (existau deja functionale) | — |

## Sistemul "la pamant" (IMPORTANT: activ implicit)
Un jucator care ar muri (nu din `/kill` sau gol) ramane la pamant: sangereaza 240 s -> stop cardiac 120 s -> moarte. Geanta stabilizeaza (timpul sta), defibrilatorul salveaza din stop cardiac. Oprire: `downed.enabled: false` in `items.yml`. Starea se pastreaza peste restart; cine moare cat e offline moare la reconectare.

## Limite tehnice (fara simulari)
- Nimic din acest sistem nu a fost testat in multiplayer; verificat doar prin CI (compilare + teste unitare ale logicii pure).
- **Radio**: text pe canale, nu voce (Plasmo Voice nu e integrat; ar cere API-ul lor pe server).
- **Camera**: serverul nu poate primi o captura de ecran de la client; poza e o inregistrare cu metadate + item.
- **Bodycam**: jurnal eveniment-timp-loc, nu video.
- **Diagnoza/reparatie** actioneaza pe datele reale salvate ale vehiculului (caroserie, combustibil, km, ITP, RCA); nu schimba fizica vehiculului MrCrayfish.
- Daca modul vehiculelor nu raspunde, scannerul/trusa spun asta si piesa se returneaza.

## Lista de verificare in joc (de rulat pe server)
Pentru fiecare item: folosire normala; fara permisiune/job; distanta prea mare; fara item; deconectare in timpul actiunii; duplicare (`/give` copie, `/clear`, drop+pick); persistenta dupa restart.
Fluxuri: bodycam + raport coleg; mecanic scaneaza -> comanda piese -> repara; EMS stabilizeaza -> coleg transporta -> tratament; reporter fotografiaza -> publica; taxi: comanda + aparat; politie: proba -> potrivire -> MDT -> dosar.

## Teste automate
`plugin/src/test/.../items/ItemCoreTest.java` (CI): serial, pauze, activitati (anulare), catuse, stari medicale, sanse de defibrilare, amprente, reguli permisiune/distanta.
