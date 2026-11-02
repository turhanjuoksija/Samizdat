# Samizdat Development & Versioning Notes 🛡️

## 🚨 Pending Verification (Requires 2-Device Testing) 📱📱
- [ ] **Map Visuals**: Confirm that passengers see "Car 🚗" icons for real-time driver locations and "Signal 📡" for grid-based offers.
- [ ] **Map Interactions**: Confirm that the "Accept ✅ / Decline ❌" dialog appears correctly on the map for passengers and triggers the correct flow.
- [ ] **Offer Deduplication**: Confirm that only one offer appears per driver on the map/list (latest wins).

Tämä tiedosto sisältää kriittisiä huomioita projektin riippuvuuksista ja pakkausasetuksista. Lue tämä ennen kuin päivität kirjastoja tai muutat Gradle-asetuksia!

## 1. Tor & Native Libraries (Packaging) 📦
Tor ajetaan **sovelluksen omassa prosessissa** Guardian Projectin `tor-android`-kirjastolla (`info.guardianproject:tor-android`, JNI `libtor.so` + `TorService`). Ohjaus `jtorctl`:llä (`info.guardianproject:jtorctl`). (Vaihdettu kmp-torista 2.11.2026, ks. §19.)
- **Packaging**: `build.gradle.kts` → `packaging.jniLibs`: `pickFirsts`/`keepDebugSymbols` vain `libtor.so`:lle. `libtorexec.so`/`libevent.so` -säännöt poistettu (ei enää käytössä).
- **compileSdk**: tor-android 0.4.9.12+ vaatii compile SDK **37.1** (AAR: `minCompileSdk=37`, `minCompileMinorSdk=1`) → `compileSdk { version = release(37) { minorApiLevel = 1 } }`. `targetSdk` on erikseen (34).
- **kapt + Java 24 -tavukoodi (TÄRKEÄ)**: tor-androidin luokat ovat class 68 (Java 24). kapt kääntää Kotlin-luokista Java-stubit JDK 17:n javacilla, joka ei osaa lukea niitä ("class file has wrong version 68.0"). Siksi `TorService`-tyyppiä **ei saa** käyttää kentän, parametrin tai paluuarvon tyyppinä missään tämän moduulin Kotlin-luokassa, vain funktioiden rungoissa (`SamizdatTorManager.torService: Service?` + cast). Pysyvä korjaus: Room → KSP (poistaa kaptin) tai JDK 24+ toolchain.
- **Manifest**: AAR tuo `org.torproject.jni.TorService`-palvelun (ei exported) automaattisesti.
- **ÄLÄ** lisää kmp-tor-riippuvuuksia takaisin rinnalle (kaksi Tor-toteutusta + `libtor.so`-konflikti).

## 2. Kotlin Metadata & Metadata-konfliktit 🧩
AGP 8.x + Kotlin 1.9.x aikainen "Duplicate archive copy" -ongelma. `kotlinx-metadata-jvm`-kapt-kikka ja kotlin-stdlib/coroutines-pakotus (`resolutionStrategy`) on **poistettu** (tarpeettomia AGP 9 + Kotlin 2.3:lla ja ilman kmp-toria).
- **Sääntö**: `META-INF/kotlin-stdlib-jdk*.kotlin_module` tiedostoille on asetettu `pickFirst` (vaaraton, saa jäädä).
- **Sääntö**: `META-INF/INDEX.LIST` ja `DEPENDENCIES` on asetettu `excludes` listalle.
- **Room + Kotlin-versio**: Room 2.8.5:n kapt-prosessori lukee vain Kotlin-metadataa <= 2.3.0. Kotlin 2.4 → `kaptDebugKotlin` kaatuu ("Metadata instance has version 2.4.0"). Siksi Kotlin on lukittu **2.3.x**:ään juuren `build.gradle.kts`:ssä. Nosta vasta, kun Room tukee 2.4:ää tai siirrytään KSP:hen.

## 3. Tor Konfiguraatio (SOCKS Port) 🔌
- **torrc**: `SamizdatTorManager` kirjoittaa ennen käynnistystä `TorService.getTorrc(context)` → `SOCKSPort auto` + `HTTPTunnelPort 0`. torrc ohittaa TorServicen `torrc-defaults`-tiedoston, joka muuten valitsisi 9050/8118 jos vapaana.
- **Portin luku**: kun TorService lähettää `STATUS_ON`, portti luetaan `GETINFO net/listeners/socks` (muoto `"127.0.0.1:NNNN"`) → `socksPort`.
- **Portti**: SOCKS-portti tulee aina lukea dynaamisesti `torManager.socksPort.value` kautta. **ÄLÄ** käytä hardkoodattua porttia 9050.
- **setEvents**: Jos kutsut `TorControlConnection.setEvents(...)`, listassa on oltava `STATUS_CLIENT`, muuten TorService ei tunnista valmistumista. Bootstrap-% tulee `STATUS_CLIENT … BOOTSTRAP PROGRESS=NN` -tapahtumista (raw event listener).

## 4. Samizdat Envelope Protocol (v1 & v2 DHT) 📨
Viestit kulkevat JSON-kääreessä ("Envelope").
**v1 (Standard):** Suora viestintä tunnettujen vertaisten välillä.
**v2 (DHT Store):** Käytetään `type: "dht_store"`, kun viesti tallennetaan verkkoon tiettyyn Grid-soluun.

**DHT Rakenne (v2):**
```json
{
  "v": 2,
  "type": "dht_store",
  "grid_id": "RG-3345-681",
  "sender_onion": "...",
  "sender_nick": "Nimi",
  "content": "RIDE OFFER: ...",
  "timestamp": 12345678,
  "ttl": 3600
}
```

## 5. RadioGrid (RG) & Kademlia DHT 🌐
RadioGrid jakaa maailman ~500m x 500m kokoisiin "sääruutuihin" (Grid cells).
- **ID-Formaatti**: `RG-LAT-LON` (perustuu `RadioGridUtils` laskentaan).
- **Routing**: Käytämme Kademlia-pohjaista XOR-etäisyyttä puhelinten välillä määrittämään, kuka vastaa minkäkin gridin datasta.
- **Sync**: Sovellus suorittaa 15 sekunnin välein taustapäivityksen, joka hakee uudet ilmoitukset reittisi varrelta olevista ruuduista.

## 6. Road-Based Routing (OSRM) 🛣️
Emme käytä enää suoria "linnun tietä" -viivoja.
- **Service**: `RoutingService.kt` käyttää OSRM Demo API:a (`project-osrm.org`) reitin laskemiseen.
  - **HUOM**: Tämä on ilmainen demo-palvelu, jota **ei saa käyttää kaupallisesti tai raskaaseen liikenteeseen**. Lopullisessa tuotantoversiossa tarvitset oman OSRM-serverin tai maksullisen reitityspalvelun (esim. Mapbox).
- **Grids from Route**: `RadioGridUtils.getRouteGridsFromPolyline()` muuntaa tieuran listaksi Grid-ID:itä, joihin kuljettajan ilmoitus propagoidaan.
- **User-Agent (PAKOLLINEN) - 21.10.2026**: OSRM demo palauttaa HTTP 403 Androidin oletus-UA:lle (`Dalvik/...`), ja OSM-tiilipalvelin palauttaa "Access blocked" -tiilen UA:ille `com.example.*` ja `osmdroid`. Molemmat käyttävät `RoutingService.USER_AGENT` (`Samizdat/<versionName> (+github-url)`). **ÄLÄ** palauta `URL.readText()`-kutsuja tai `context.packageName`-UA:ta.
- **Fallback (linnuntie)**: Jos reitin osuus epäonnistuu, `RouteManager` käyttää osuudelle suoraa viivaa + naapuriruudut (`getNeighborGrids(r=1)`). `routeGrids` ei siis koskaan jää tyhjäksi, ja `routeIsApproximate = true` piirtää reitin katkoviivana.
- **Tietosuoja**: Karttatiilet kulkevat **vain Torin kautta** (Vaihe 2, alla). OSRM-reittipyynnöt kulkevat yhä clearnetin kautta, mutta reitit lasketaan ensisijaisesti laitteessa BRouterilla (alla).
- **Offline-reititys (BRouter, Vaihe 1) - 21.10.2026**:
  - `RouteManager` käy `RouteEngine`-moottorit läpi osuuskohtaisesti: `BRouterEngine` (offline) → `OsrmEngine` (väliaikainen online-vara) → suora viiva.
  - Riippuvuus: `com.github.abrensch.brouter:brouter-core:v1.7.9` JitPackista (`settings.gradle.kts`, content-suodatettu vain tälle ryhmälle). **ÄLÄ päivitä v1.7.10:een**: se ei käänny JitPackissa (tila "Error"). Tarkista `https://jitpack.io/api/builds/com.github.abrensch/brouter` ennen päivitystä.
  - Profiili: `assets/brouter/car-vario.brf` + `lookups.dat` (`car-fast.brf` EI ole olemassa). `lookups.dat` pitää olla samassa kansiossa kuin `.brf` ja sen versio täsmätä `.rd5`-segmentteihin (muuten "lookup version mismatch"). `BRouterEngine` kopioi nämä `filesDir/brouter/profiles2/`.
  - Data: `filesDir/brouter/segments4/E20_N60.rd5` jne. (5°x5° ruudut, 10–40 Mt). `SegmentManager` lataa ne `https://brouter.de/brouter/segments4/` **Torin SOCKS-portin kautta** (`torManager.socksPort.value`, ei hardkoodattu). Lataus tallennetaan `.part`-tiedostoon, tarkistetaan koko (max 100 Mt) ja nimetään vasta onnistuneena.
  - BRouterin staattiset välimuistit eivät ole säieturvallisia → `BRouterEngine` serialisoi ajot `Mutex`illa. Reitti peruuntuu `RoutingEngine.terminate()`-kutsulla.
  - Jos osuus tarvitsee segmentin jota ei ole asennettu, `BRouterEngine` palauttaa `null` (ei kaadu) ja seuraava moottori yrittää.
  - UI: Settings → "Offline Routing" -kortti: "Download missing for route" (aktiivisen reitin puuttuvat ruudut, sama per-osuus bbox-sääntö kuin `BRouterEngine`issä → `RouteManager.requiredSegments()`), "Download region around me", sekä Suomen aluelista `SegmentManager.FINLAND_REGIONS` (koot vain UI-vihje, päivitä tarvittaessa brouter.de-listauksesta). Latausjono näyttää (n/N), ja **Cancel** peruu sekä coroutinen että käynnissä olevan OkHttp-kutsun (`cancelActiveDownload()`; estynyt Tor-connect ei huomaa pelkkää coroutine-peruutusta).
  - HUOM bbox-sääntö: vinot osuudet voivat vaatia "turhia" ruutuja (esim. Hanko→Kotka vaatii myös E25_N55). Tämä on tietoinen varovaisuus.
  - Desktop-spike (Helsinki→Tampere, E20_N60, car-vario): 179,2 km, 1772 solmua, ~7 s.
- **Karttatiilet Torin kautta (Vaihe 2) - 21.10.2026**:
  - `MapComposable` luo `MapView(context, TorMapTileProvider(...))`. `TorMapTileProvider` = `MapTileProviderBasic`, jonka `createDownloaderProvider()` asettaa `TorTileDownloader`in (OkHttp + SOCKS → ei paikallista DNS:ää).
  - **ÄLÄ** käytä osmdroidin `Configuration.setHttpProxy()`:a: se käyttää `HttpURLConnection`ia, joka resolvoi DNS:n paikallisesti Androidilla (vuoto).
  - Tiukka tila: kun Tor ei ole valmis, latausäikeet odottavat max 90 s ja luovuttavat. **Ei clearnet-varaa.** Välimuistissa olevat tiilet näkyvät silti. `TorTileDownloader.socksPortProvider` asetetaan `MainActivity`ssä ja palauttaa portin vasta kun `isBootstrapped == true`.
  - Testattu desktopilla (5 eri exit-solmua): `tile.openstreetmap.org` palauttaa HTTP 200 Torin kautta (~0,6–2 s/tiili).
  - Tiedossa: tiilipyynnöt jakavat Tor-circuitin muun sovellusliikenteen kanssa (ei SOCKS-auth-eristystä). OSRM-varareititys kulkee yhä clearnetin kautta.

## 7. .onion Osoitteiden Käsittely (`OnionUtils`) 🌐
Tor v3 -osoitteet ovat 56 merkkiä pitkiä. Sovellus vaatii `.onion` päätteen, jotta Android ja Tor osaavat reitittää viestit oikein.
- **Utility**: Käytä `OnionUtils.ensureOnionSuffix(address)` aina kun tallennat tai lähetät viestejä. 

## 8. Tietokanta (Room) 🗄️
- Käytämme tällä hetkellä `fallbackToDestructiveMigration()` asetusta.
- **Varoitus**: Jokainen `version` numeron korotus `AppDatabase.kt` tiedostossa **PYYHKII KAIKKI TIEDOT**.

## 9. Verkkoviestintä & Aikakatkaisut ⏳
- **Timeout (lähetys)**: `ConnectionManager.sendMessage()` käyttää 30 sekunnin connect-timeoutia Tor-yhteyksille.
- **Timeout (vastaanotto)**: Idle-yhteydet katkaistaan automaattisesti 60 sekunnin jälkeen (`SOCKET_TIMEOUT_MS`).
- **Viestin kokorajoitus**: Yksittäinen viesti saa olla enintään 64 KB (`MAX_MESSAGE_SIZE = 65_536`). Ylitys katkaisee yhteyden.
- **Nopeusrajoitus**: Enintään 30 viestiä / 60 sekuntia per IP-osoite (`RATE_LIMIT_MAX_MESSAGES`). Ylitys palauttaa `RATE_LIMITED`.
- **Background Sync**: DHT-haku ja Status Broadcast tapahtuvat taustasäikeissä (`viewModelScope`) jumiutumisen välttämiseksi.

## 11. Tekninen ympäristö & Kääntäminen (Java 17) ☕
- **Versiot (2.11.2026)**: AGP **9.4.1**, Gradle **9.8.2**, Kotlin **2.3.21** (lukittu, ks. §2), compile SDK **37.1**, targetSdk 34, Java/JVM target **17**.
- **AGP 9 built-in Kotlin**: `org.jetbrains.kotlin.android`-pluginia **ei** sovelleta `app/`-moduulissa (juuressa `apply false` vain Kotlin-version lukitsemiseksi). kapt tulee `com.android.legacy-kapt`-pluginista, Compose `org.jetbrains.kotlin.plugin.compose`-pluginista (ei `composeOptions`ia). `kotlinOptions {}` → `kotlin { compilerOptions { … } }`.
- **Java versio**: AGP 9.4 vaatii **JDK 17**. HUOM: tor-androidin Java 24 -tavukoodi rikkoo kaptin, jos `TorService` päätyy stubiin (ks. §1).
- **SDK**: Puuttuvat `platforms;android-37.0`/`android-37.1` latautuivat AGP:n automaattisesti (lisenssit hyväksytty). `cmdline-tools`/`sdkmanager` ei ole asennettuna.
- **Ongelmat**: Jos saat virheen `Android Gradle plugin requires Java 17 to run.`, varmista että Java 17 on asennettu (`sudo apt install openjdk-17-jdk` Linuxilla).
- **Konfiguraatio**: Jos IDE ei löydä Javaa, lisää `gradle.properties` tiedostoon rivi: `org.gradle.java.home=/usr/lib/jvm/java-17-openjdk-amd64` (polku voi vaihdella käyttöjärjestelmästä riippuen).
- **Gradle JVM Args**: Android-kehitinympäristössä `kapt` ja `compose` saattavat vaatia enemmän muistia. `gradle.properties` tiedostossa on `org.gradle.jvmargs=-Xmx2048m`, jotta kääntäminen ei kaadu muistiin (OutOfMemoryError).

---
*Päivitetty viimeksi: 2026-11-02 (Tor-päivitys: tor-android 0.4.9.14, AGP 9)*

## 12. Kartan ja Käyttöliittymän Päivitykset (UI/UX) - 24.1.2026 🗺️
- **Undo-toiminto**: Korvattu kaksi erillistä poistonappia yhdellä `Undo (↩️)` -painikkeella, joka poistaa viimeisimmän reittipisteen.
- **Reittipisteet**: Käytämme nyt numeroituja ("1", "2"...) markkereita emoji-kokeilujen sijaan selkeyden vuoksi.
- **Rooli ja Ikonit**: Aloituspisteen ("Start") ikoni ja väri muuttuvat automaattisesti roolin mukaan (🚗/🔴 Driver, 🙋/🟢 Passenger).
- **Pakotettu Roolivalinta**: Karttaa ei voi käyttää reitin luomiseen ennen kuin rooli on valittu (Overlay aukeaa automaattisesti).
- **Broadcast Logiikka**: Broadcast menee automaattisesti pois päältä (OFF), jos reittiä muokataan (Undo tai uusi piste), jotta väärää tietoa ei lähetetä.
- **Roolinvaihto bugi korjattu**: Roolin vaihtaminen tyhjentää nyt varmuudella _kaikki_ vanhat reittitiedot, estäen haamuviivojen jäämisen kartalle.
- **Mukautetut Karttamerkit**: Toteutettu emoji-pohjaiset pyöreät ikonit aloituspisteelle (🏠/🚗/🙋) ja numeroitavat kultaiset pallot reittipistille (1, 2, 3...).
- **Suorat viivat poistettu Drivereilta**: Syaaniväriset "varaviivat" eivät enää näy Driver-roolissa, vain Passenger-roolissa jos tiepohjaista reittiä ei ole laskettu.

## 13. Tietoturvaparannukset (Security Hardening) - 12.2.2026 🔒
- **Päivityksen downgrade-suojaus**: `UpdateManager` hylkää päivitykset, joiden versiokoodi on <= nykyinen asennettu versio. Estää hyökkääjää pakottamasta vanhaa, haavoittuvaa versiota.
- **FileProvider rajoitettu**: `file_paths.xml` sallii nyt vain `updates/`-alihakemiston (aiemmin koko cache-hakemisto `path="."`). APK-tiedostot tallennetaan nyt `cacheDir/updates/` kansioon.
- **NSD/mDNS poistettu**: `NsdHelper.kt` poistettu kokonaan, WiFi-oikeudet (`ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`) poistettu manifestista. Kaikki vertaisviestintä kulkee nyt vain Torin kautta — paikallisverkkoon ei vuoda mitään.
- **Input-validointi**: `ConnectionManager` rajoittaa viestikoon (64 KB), katkaisee idle-yhteydet (60s), ja rajoittaa viestien määrää per IP (30/min). Estää OOM- ja flooding-hyökkäykset.
- **SOCKS-portti**: `UpdateManager` käyttää nyt dynaamista Tor-porttia (`torManager.socksPort.value`) hardkoodatun 9050:n sijaan.

https://github.com/turhanjuoksija/Samizdat
https://github.com/turhanjuoksija/Samizdat/releases

## 14. UI/UX Design Principles & Markers - 18.2.2026 🎨
- **Kieli**: Käyttöliittymän kieli on aina **Englanti** (English). Ei suomenkielisiä tekstejä UI-komponenteissa.
- **Deduplication (Requests & Offers)**: Samalta Onion-osoitteelta näytetään vain **uusin** viesti (oli kyseessä kyytipyyntö kuljettajalle tai kyytitarjous matkustajalle).
- **Visual Linking (A, B, C...)**: Kyytipyynnöt numeroidaan kirjaimin listan otsikossa (`A — Ride Request`) ja kartalla (`Teardrop A`) yhteyden luomiseksi.
- **Karttamarkkerien Värikoodit (Teardrop-tyyli)**:
    - **Passenger**: Keltainen (`#FFD600`) pisara. Sisällä kirjain (A, B...) tai `🙋` jos rooli on pelkkä 'PASSENGER' ilman aktiivista pyyntöä.
    - **Driver**: Syaani (`#00BCD4`) pisara. Sisällä auto `🚗`.
- **Offers Tab**: "Show on Map" -nappi (`[ 🗺️ ]`) toimii on/off-kytkimenä. Raakaa koordinaattidataa (lat/lon luvut) ei näytetä korteissa, vain "Pickup location available" -indikaattori.

## 15. Future Rides (Scheduling) - 19.2.2026 🗓️
- **Tietokanta**: `RideIntent` -entiteetti (`ride_intents` -taulu). Kentät: `type` (OFFER/REQUEST), sijainti, `departureTime` (epoch millis), `status` (ACTIVE/EXPIRED/CANCELLED). Tietokannan versio nostettu **13**:een.
- **GridMessage**: Lisätty `departureTime: Long?` ja `flexibleTimeWindow: Long?`. `null` = live-kyyti, asetettu = tulevaisuuden kyyti.
- **DHT-protokolla**: Uudet kentät `dep_t` ja `time_win` JSON-viestissä. Vanha protokolla ei ymmärrä näitä — käyttäjän pitää päivittää (fresh install).
- **Spam-esto**: Max **5 aktiivista** `RideIntent`-ilmoitusta per käyttäjä. Tarkistetaan paikallisesti ennen tallennusta.
- **TTL**: Tulevien kyytien TTL on 24h. Sovellus **uudelleenjulkaisee** aktiiviset intentit automaattisesti 15 min välein kun sovellus on auki.
- **Offline-käyttäytyminen**: P2P-verkossa ilmoitus katoaa kun TTL umpeutuu. Jos käyttäjä on offline 3 päivää, ilmoitukset häviävät verkosta. Tämä on hyväksyttävää, koska laite ei voi lähettää viestejä suljettuna.
- **UI (FutureRidesScreen)**: "My Schedule" (omat ilmoitukset) + "Future Offers on Route" (löydetyt tarjoukset). ❌ dismiss-nappi, 👍 "Interested" -pikanappi, 💬 "Chat" -nappi.
- **Chat-First periaate**: Tulevaisuuden kyydit ovat "keskustelun aloittajia". Tavoite ei ole automaattinen varaus vaan yhteydenotto kuskin kanssa keskusteluun.
- **TODO**: Chat-viestien linkittäminen tiettyyn tarjoukseen/matkaan. Nykyään Chat-sivu ei tiedä mihin kyytiin viesti liittyy.

## 16. P2P Bootstrapping via Nostr (Sybil-resistant) 🌐 - 22.2.2026
Sovellus käyttää Tor-verkon `.onion` osoitteita vertaisviestintään, mutta uusien käyttäjien löytäminen (bootstrapping) vaatii ulkopuolisen kerroksen.
- **Nostr Relays**: Käytämme kaikille avoimia Nostr relay -palvelimia (kuten `wss://relay.damus.io` ja `wss://nos.lol`) julkistaaksemme ja löytääksemme muiden käyttäjien `.onion` osoitteita.
- **Sybil-suojaus (NIP-13 PoW)**: Nostr on täysin avoin ja kuka tahansa voi luoda loputtomasti feikki-avaimia. Tämän estämiseksi vaadimme kryptografisen Proof-of-Workin (Työtodiste, NIP-13). 
  - Kun puhelin julkaisee osoitteensa, sen täytyy laskea SHA-256 tiivistettä kunnes saavutetaan *Difficulty 22* (ID:n pitää alkaa ainakin viidellä nollalla ja parilla bitillä). Tämä on tarkoituksella hidasta (kestää useamman minuutin puhelimella), mikä tekee massiivisesta spammaamisesta mahdotonta yhdelle hyökkääjälle.
  - Vain viestit, joiden PoW-vaatimus täyttyy, hyväksytään muilta.
- **Tallennetut Tapahtumat ja Expiration (NIP-01 & NIP-40)**: Käytämme Nostr *Replaceable* Kind `10337`.  Nämä tallentuvat Relay-palvelimille. Jotta roskan määrä pysyy kurissa, lisäämme eventtiin NIP-40 `"expiration"` -tagin (7 päivää eteenpäin), jolloin Relay voi siivota sen automaattisesti pois.
- **Rate-Limiting**: Puhelin julkaisee ja louhii PoW:n vain **kerran 24 tunnissa** (käytämme SharedPreferencesia tämän muistamiseen), paitsi jos `.onion` -osoite muuttuu, jolloin se julkaistaan välittömästi. Samalla oma sipuliosoite suodatetaan pois löydetyistä tuloksista.

## 17. App Update Gossiping (Nostr + P2P) - 23.2.2026 🔄
Sovelluksen päivitykset levitetään P2P-verkossa ilman erillistä käyttöliittymää (Developer UI) sovelluksessa.
- **Nostr Initial Broadcast**: Kehittäjä julkaisee uuden APK:n verkkoon tekemällä Nostr-verkon kautta tapahtuman (Kind `10338`). Tapahtuma sisältää tagit `samizdat_ver`, `samizdat_url` ja `samizdat_sig`.
  - Toisin kuin peer-löydössä, tässä **ei** vaadita PoW-laskentaa, koska luottamus perustuu sovelluksen sisäänrakennettuun kehittäjän julkiseen avaimeen (Signature verification).
- **P2P Gossip Propagation**: Kun mikä tahansa vertaislaite (peer) lataa ja vahvistaa uuden päivityksen (Signature OK), se tallentaa viestin välimuistiin (`cachedUpdateJson`).
- **Passive Discovery**: Status-viestit sisältävät nyt uuden kentän: `"app_v"`. Kun laite, jolla on välimuistissa uusi päivitys, ottaa vastaan status-viestin laitteelta jolla on pienempi `"app_v"`, se lähettää päivitysviestin sille automaattisesti.
- **Workflow**: 
  1. Increment `versionCode` in `app/build.gradle.kts`.
  2. Build APK: `./gradlew assembleRelease`
  3. Run the automated script: `~/projects/samizdat/publish_update.sh` (This script gathers the APK, hashes it, signs the hash with `private_rsa.pem` using SHA256withRSA, publishes the `Kind 10338` event to Nostr, and stages the APK for hosting.)
  4. Ensure Tor is running and serve the staging directory via Python HTTP server: `cd ~/samizdat_updates/ && python3 -m http.server 8080`.
  5. **Note on Crypto**: All developer update signatures and internal identity keys run on **SHA256withRSA**, not Ed25519. Android devices running API < 33 (Android 13) do not have native KeyStore support for Ed25519, which caused silent signature validation failures on older devices.

## 18. Tietoturvaparannukset v2 (Security Hardening Phase 2) - 3.3.2026 🔒
Toteutettiin laaja koodikatselmointi ja korjattiin löydetyt kriittiset ja korkean tason haavoittuvuudet:
- **Nostr Schnorr Signature Verification (Kriittinen)**: `NostrBootstrapManager` vahvistaa nyt Nostr-eventtien `sig`-kentän (BIP340 Schnorr) käyttämällä eventin ID:tä, estäen väärennettyjen onion-osoitteiden ja päivitystapahumien syöttämisen. ID:n uudelleenlaskenta on vain varoitus (yhteensopivuus mm. `nak`-työkalun kanssa).
- **Android Backup poistettu (Kriittinen)**: `AndroidManifest.xml` -> `allowBackup="false"`. Estää Torin `.onion` yksityisavaimen ja Room-tietokannan viennin ADB-backupilla.
- **SOCKS Port Null-Safety (Korkea)**: Poistettu kaikki 8+ tapausta `?: 9050` hardkoodauksesta. Yhteyksiä ei yritetä muodostaa ennen kuin Tor-palvelu on antanut dynaamisen SOCKS-portin.
- **Strict Tor Routing (Korkea)**: `ConnectionManager.sendMessage` hyväksyy vain `.onion` päätteisiä osoitteita ja verkkovirheet heittävät `SecurityException`. Estää suorat TCP IP -yhteydet ja IP:n paljastumisen.
- **APK Download Size Limit (Korkea)**: `UpdateManager` rajoittaa päivitystiedostot enintään 100 MB kokoiseksi (`MAX_APK_DOWNLOAD_BYTES`), suojaten laitteen tallennustilan täyttämiseltä.

## 19. Tor-päivitys: kmp-tor → tor-android 0.4.9.14 - 2.11.2026 🧅
Syy: kmp-torin Tor oli 0.4.8.13 (EOL 1.6.2026, konsensus ei enää suosittele). kmp-tor-resource ei ole julkaissut helmikuun jälkeen.
- **Kirjastot**: `info.guardianproject:tor-android:0.4.9.14` (Tor 0.4.9.14, OpenSSL 3.5.x) + `jtorctl:0.4.5.7` + `androidx.localbroadcastmanager`.
- **`SamizdatTorManager`**: julkinen API ennallaan (`socksPort`, `onionAddress`, `isBootstrapped`, `statusMessage`, `startTor()`, `stopTor()`, `bootstrapManager`). Sisäisesti: torrc → `LocalBroadcastManager`-vastaanotin (`ACTION_STATUS`/`ACTION_ERROR`) → `bindService(TorService)` → `STATUS_ON` → `net/listeners/socks` → `ADD_ONION`.
- **Onion-avain (osoite säilyy)**: `filesDir/onion_v3_private_key` = 64 tavua raakaa ED25519-laajennettua avainta (sama muoto kuin kmp-torilla). Olemassa oleva avain → `ADD_ONION ED25519-V3:<base64>`. Uusi → `NEW:ED25519-V3`, Torin palauttama `onionPrivKey` dekoodataan ja tallennetaan samaan tiedostoon (vain jos tasan 64 tavua). Lokiin kirjataan vain pituus. Hidden service: virtuaaliportti 80 → `127.0.0.1:12345`.
- **Rajoitus – uudelleenkäynnistys samassa prosessissa**: Tor ajetaan JNI:llä (`tor_run_main`), ja TorServicessa on staattinen lukko. Toria ei saa ajaa kahdesti samanaikaisesti, ja toinen ajo samassa prosessissa on upstreamissa "best effort" (tor bug #23847). `PeersViewModel.onCleared()` kutsuu `stopTor()`, joten Back + avaus uudelleen käynnistää Torin samassa prosessissa. `startTor()` odottaa siksi vähintään 5 s edellisen pysäytyksen jälkeen. Jos tämä osoittautuu epävakaaksi, vaihtoehto on olla pysäyttämättä Toria `onCleared()`issa.
- **Siivous**: Ensimmäisellä käynnistyksellä poistetaan kmp-torin `filesDir/torservice` ja `cacheDir/torservice` (pienellä kirjoitettuna; tor-androidin omat hakemistot ovat `app_TorService` ja `cacheDir/TorService`). Avaintiedostoon ei kosketa.
- **Käännösympäristö** päivitetty samassa yhteydessä (§11): AGP 9.4.1, Gradle 9.8.2, Kotlin 2.3.21, Room 2.8.5, Compose BOM 2026.09.00, compile SDK 37.1.
- **kapt & Java 24 -gotcha (ks. §1 & §2)**: `TorService`-tyyppi pidetty pois kenttien/metodien tyypeistä (kapt JDK 17 ei lue class 68 -tavukoodia). Kotlin lukittu 2.3.x Room 2.8.5 -kaptin metadatarajoituksen vuoksi. Pysyvä ratkaisu myöhemmin: Room → KSP.
- **Tila & Testaus**: Käännökset (`assembleDebug`, `assembleRelease`) OK. Testattu laitteella (Nokia X30 5G, Android 14): .onion-osoite säilyi identtisenä vanhan kmp-tor-asennuksen (build 17) päälle päivitettäessä, Tor 0.4.9.14 yhdisti verkkoon ja loi hidden servicen. Back-napin jälkeinen uudelleenkäynnistys samassa prosessissa testattu ja toimii 5 s varoajan ansiosta. Seuraavaksi: 2-laitteen P2P-viestintätesti.
