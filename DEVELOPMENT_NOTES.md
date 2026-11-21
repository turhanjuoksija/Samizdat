# Samizdat Development & Versioning Notes 🛡️

## 🚨 Pending Verification & Upcoming Tasks 📱📱
- [ ] **2-Device Messaging (Build 22)**: Confirm that `.onion` handshake, ping, and chat work end-to-end after the `127.0.0.1` loopback bind fix (§17).
- [ ] **TODO (Privacy / Nostr over Tor)**: `NostrBootstrapManager` käyttää suoraa `OkHttpClient`-yhteyttä ilman Torin SOCKS-proxya, eli Nostr-relay-palvelimet näkevät laitteen oikean IP-osoitteen yhdessä julkaistun `.onion`-osoitteen kanssa. Reititetään Nostr WebSocket -yhteydet Torin SOCKS-portin (`torManager.socksPort.value` + `InetSocketAddress.createUnresolved` tai OkHttp SOCKS DNS-eristys) kautta heti kun perusviestintä on varmistettu (ks. §14).
- [ ] **TODO (Chat / Messages Cleanup & UX)**: Viestiketjuihin (`Messages` / `ChatScreen`) poistomahdollisuus / piilotus (kuten kontaktien roskakori), vanhojen ketjujen automaattinen vanheneminen ("maatuminen"), suodatus ja muut viestinäkymän parannukset (ks. `video/2026-11-18_v22/Screenshot_20261118-223825.png`).
- [ ] **Map Visuals**: Confirm that passengers see "Car 🚗" icons for real-time driver locations and "Signal 📡" for grid-based offers.
- [ ] **Map Interactions**: Confirm that the "Accept ✅ / Decline ❌" dialog appears correctly on the map for passengers and triggers the correct flow.
- [ ] **Offer Deduplication**: Confirm that only one offer appears per driver on the map/list (latest wins).

Tämä tiedosto sisältää kriittisiä huomioita projektin riippuvuuksista ja pakkausasetuksista. Lue tämä ennen kuin päivität kirjastoja tai muutat Gradle-asetuksia!

## 1. Tor & Native Libraries (Packaging) 📦
Tor ajetaan **sovelluksen omassa prosessissa** Guardian Projectin `tor-android`-kirjastolla (`info.guardianproject:tor-android:0.4.9.14`, JNI `libtor.so` + `TorService`). Ohjaus `jtorctl`:llä (`info.guardianproject:jtorctl:0.4.5.7`) + `androidx.localbroadcastmanager`.
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

## 3. Tor Konfiguraatio (SOCKS Port & Hidden Service) 🔌
- **torrc**: `SamizdatTorManager` kirjoittaa ennen käynnistystä `TorService.getTorrc(context)` → `SOCKSPort auto` + `HTTPTunnelPort 0`. torrc ohittaa TorServicen `torrc-defaults`-tiedoston, joka muuten valitsisi 9050/8118 jos vapaana.
- **Portin luku**: kun TorService lähettää `STATUS_ON`, portti luetaan `GETINFO net/listeners/socks` (muoto `"127.0.0.1:NNNN"`) → `socksPort`.
- **Portti**: SOCKS-portti tulee aina lukea dynaamisesti `torManager.socksPort.value` kautta. **ÄLÄ** käytä hardkoodattua porttia 9050.
- **setEvents**: Jos kutsut `TorControlConnection.setEvents(...)`, listassa on oltava `STATUS_CLIENT`, muuten TorService ei tunnista valmistumista. Bootstrap-% tulee `STATUS_CLIENT … BOOTSTRAP PROGRESS=NN` -tapahtumista (raw event listener).
- **Onion-avain & Hidden Service**: `filesDir/onion_v3_private_key` sisältää 64 tavua raakaa ED25519-laajennettua avainta. Olemassa oleva avain → `ADD_ONION ED25519-V3:<base64>`; uusi → `NEW:ED25519-V3` (tallennetaan vain jos tasan 64 tavua; lokiin kirjataan vain pituus). Kutsutaan `detach = true`, osoite tallennetaan `filesDir/onion_v3_hostname`-tiedostoon, ja virtuaaliportti 80 ohjataan kohteeseen `127.0.0.1:12345`.
- **Uudelleenkäynnistys samassa prosessissa**: Tor ajetaan JNI:llä (`tor_run_main`), ja TorServicessa on staattinen lukko (tor bug #23847). `startTor()` odottaa vähintään 5 s edellisen `stopTor()`-kutsun jälkeen.
- **Elinkaari & `PeersViewModelFactory`**: `MainScreen`in (`MainActivity.kt`) `BackHandler` siirtää `MAP`-välilehdellä sovelluksen taustalle (`moveTaskToBack(true)`) tuhoamatta `MainActivity`:a, `PeersViewModel`:ia tai Toria. `SamizdatTorManager`, `ConnectionManager`, `PeerRepository`, `UpdateManager`, `ApkSeeder`, `SegmentManager` ja `TorTileDownloader.socksPortProvider` luodaan aina `PeersViewModelFactory.create()`-metodissa (ei `MainActivity.onCreate()`issa), jotta konfiguraatiomuutokset eivät luo duplikaatteja eivätkä nollaa tiililataajan SOCKS-porttia.
- **kmp-tor-siivous**: Ensimmäisellä käynnistyksellä poistetaan vanhat `filesDir/torservice` ja `cacheDir/torservice` (avaintiedostoon ei kosketa; tor-androidin omat hakemistot ovat `app_TorService` ja `cacheDir/TorService`).

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

## 9. Verkkoviestintä & Aikakatkaisut (KRIITTINEN) ⏳
- **Loopback-sidonta (`127.0.0.1`, EI `getLoopbackAddress()`)**:
  - `ConnectionManager.startListening()` **TÄYTYY** sitoa eksplisiittisesti IPv4-osoitteeseen `127.0.0.1` (`InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1))`), jotta se täsmää `SamizdatTorManager`in `ADD_ONION Port=80,127.0.0.1:12345` -ohjaukseen.
  - **ÄLÄ KOSKAAN** käytä `InetAddress.getLoopbackAddress()`-kutsua Androidilla: Androidin AOSP-toteutus palauttaa aina IPv6-osoitteen `::1`, jolloin Torin yhteys `127.0.0.1:12345`:een hylätään ja kaikki saapuvat `.onion`-viestit kaatuvat virheeseen `SOCKS server general failure` (ks. §17).
- **Timeout & Retry (lähetys, 90 s + 3 yritystä)**:
  - `ConnectionManager.sendMessage()` käyttää **90 sekunnin** connect-timeoutia (`ONION_CONNECT_TIMEOUT_MS = 90_000`), 60 sekunnin lukutimeoutia (`SOCKET_TIMEOUT_MS = 60_000`) ja **3 yrityksen uudelleenyritystä** (`SEND_MAX_ATTEMPTS = 3`, viive 5 s / 10 s).
  - **ÄLÄ** laske connect-timeoutia takaisin 30 sekuntiin tai poista uudelleenyrityksiä: mobiiliverkossa (4G) HSv3-kuvauksen haku, vanhentuneen descriptorin päivitys ja 6 hypyn Rendezvous-piirin muodostus kestää usein 45–80 sekuntia.
- **Timeout (vastaanotto)**: Idle-yhteydet katkaistaan automaattisesti 60 sekunnin jälkeen (`SOCKET_TIMEOUT_MS`).
- **Viestin kokorajoitus**: Yksittäinen viesti saa olla enintään 64 KB (`MAX_MESSAGE_SIZE = 65_536`). Ylitys katkaisee yhteyden.
- **Background Sync**: DHT-haku ja Status Broadcast tapahtuvat taustasäikeissä (`viewModelScope`) jumiutumisen välttämiseksi.

## 10. Tekninen ympäristö & Kääntäminen (Java 17) ☕
- **Versiot (2.11.2026)**: AGP **9.4.1**, Gradle **9.8.2**, Kotlin **2.3.21** (lukittu, ks. §2), compile SDK **37.1**, targetSdk 34, Java/JVM target **17**.
- **AGP 9 built-in Kotlin**: `org.jetbrains.kotlin.android`-pluginia **ei** sovelleta `app/`-moduulissa (juuressa `apply false` vain Kotlin-version lukitsemiseksi). kapt tulee `com.android.legacy-kapt`-pluginista, Compose `org.jetbrains.kotlin.plugin.compose`-pluginista (ei `composeOptions`ia). `kotlinOptions {}` → `kotlin { compilerOptions { … } }`.
- **Java versio**: AGP 9.4 vaatii **JDK 17**. HUOM: tor-androidin Java 24 -tavukoodi rikkoo kaptin, jos `TorService` päätyy stubiin (ks. §1).
- **SDK**: Puuttuvat `platforms;android-37.0`/`android-37.1` latautuivat AGP:n automaattisesti (lisenssit hyväksytty). `cmdline-tools`/`sdkmanager` ei ole asennettuna.
- **Ongelmat**: Jos saat virheen `Android Gradle plugin requires Java 17 to run.`, varmista että Java 17 on asennettu (`sudo apt install openjdk-17-jdk` Linuxilla).
- **Konfiguraatio**: Jos IDE ei löydä Javaa, lisää `gradle.properties` tiedostoon rivi: `org.gradle.java.home=/usr/lib/jvm/java-17-openjdk-amd64` (polku voi vaihdella käyttöjärjestelmästä riippuen).
- **Gradle JVM Args**: Android-kehitinympäristössä `kapt` ja `compose` saattavat vaatia enemmän muistia. `gradle.properties` tiedostossa on `org.gradle.jvmargs=-Xmx2048m`, jotta kääntäminen ei kaadu muistiin (OutOfMemoryError).

## 11. Tietoturvaparannukset (Security Hardening) - 12.2.2026 🔒
- **Päivityksen downgrade-suojaus**: `UpdateManager` hylkää päivitykset, joiden versiokoodi on <= nykyinen asennettu versio. Estää hyökkääjää pakottamasta vanhaa, haavoittuvaa versiota.
- **FileProvider rajoitettu**: `file_paths.xml` sallii nyt vain `updates/`-alihakemiston (aiemmin koko cache-hakemisto `path="."`). APK-tiedostot tallennetaan nyt `cacheDir/updates/` kansioon.
- **NSD/mDNS poistettu**: `NsdHelper.kt` poistettu kokonaan, WiFi-oikeudet (`ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`) poistettu manifestista. Kaikki vertaisviestintä kulkee nyt vain Torin kautta — paikallisverkkoon ei vuoda mitään.
- **Input-validointi**: `ConnectionManager` rajoittaa viestikoon (64 KB), katkaisee idle-yhteydet (60s), ja rajoittaa viestien määrää per IP (30/min). Estää OOM- ja flooding-hyökkäykset.
- **SOCKS-portti**: `UpdateManager` käyttää nyt dynaamista Tor-porttia (`torManager.socksPort.value`) hardkoodatun 9050:n sijaan.

## 12. UI/UX Design Principles & Markers 🎨
- **Kieli**: Käyttöliittymän kieli on aina **Englanti** (English). Ei suomenkielisiä tekstejä UI-komponenteissa.
- **Roolivalinta & Reitin muokkaus**: Roolivalinta on pakotettu ennen reitin luontia, ja roolinvaihto tyhjentää varmuudella kaikki vanhat reittitiedot. Broadcast menee automaattisesti `OFF`-tilaan, jos reittiä muokataan (`Undo` tai uusi piste). Suora "linnuntie"-varaviiva näytetään vain `Passenger`-roolissa, ei `Driver`-roolissa.
- **Deduplication (Requests & Offers)**: Samalta Onion-osoitteelta näytetään vain **uusin** viesti (oli kyseessä kyytipyyntö kuljettajalle tai kyytitarjous matkustajalle).
- **Visual Linking (A, B, C...)**: Kyytipyynnöt numeroidaan kirjaimin listan otsikossa (`A — Ride Request`) ja kartalla (`Teardrop A`) yhteyden luomiseksi.
- **Karttamarkkerien Värikoodit (Teardrop-tyyli)**:
    - **Passenger**: Keltainen (`#FFD600`) pisara. Sisällä kirjain (A, B...) tai `🙋` jos rooli on pelkkä 'PASSENGER' ilman aktiivista pyyntöä.
    - **Driver**: Syaani (`#00BCD4`) pisara. Sisällä auto `🚗`.
- **Offers Tab**: "Show on Map" -nappi (`[ 🗺️ ]`) toimii on/off-kytkimenä. Raakaa koordinaattidataa (lat/lon luvut) ei näytetä korteissa, vain "Pickup location available" -indikaattori.

## 13. Future Rides (Scheduling) - 19.2.2026 🗓️
- **Tietokanta**: `RideIntent` -entiteetti (`ride_intents` -taulu). Kentät: `type` (OFFER/REQUEST), sijainti, `departureTime` (epoch millis), `status` (ACTIVE/EXPIRED/CANCELLED). Tietokannan versio nostettu **13**:een.
- **GridMessage**: Lisätty `departureTime: Long?` ja `flexibleTimeWindow: Long?`. `null` = live-kyyti, asetettu = tulevaisuuden kyyti.
- **DHT-protokolla**: Uudet kentät `dep_t` ja `time_win` JSON-viestissä. Vanha protokolla ei ymmärrä näitä — käyttäjän pitää päivittää (fresh install).
- **Spam-esto**: Max **5 aktiivista** `RideIntent`-ilmoitusta per käyttäjä. Tarkistetaan paikallisesti ennen tallennusta.
- **TTL**: Tulevien kyytien TTL on 24h. Sovellus **uudelleenjulkaisee** aktiiviset intentit automaattisesti 15 min välein kun sovellus on auki.
- **Offline-käyttäytyminen**: P2P-verkossa ilmoitus katoaa kun TTL umpeutuu. Jos käyttäjä on offline 3 päivää, ilmoitukset häviävät verkosta. Tämä on hyväksyttävää, koska laite ei voi lähettää viestejä suljettuna.
- **UI (FutureRidesScreen)**: "My Schedule" (omat ilmoitukset) + "Future Offers on Route" (löydetyt tarjoukset). ❌ dismiss-nappi, 👍 "Interested" -pikanappi, 💬 "Chat" -nappi.
- **Chat-First periaate**: Tulevaisuuden kyydit ovat "keskustelun aloittajia". Tavoite ei ole automaattinen varaus vaan yhteydenotto kuskin kanssa keskusteluun.
- **TODO**: Chat-viestien linkittäminen tiettyyn tarjoukseen/matkaan. Nykyään Chat-sivu ei tiedä mihin kyytiin viesti liittyy.

## 14. P2P Bootstrapping via Nostr (Sybil-resistant) 🌐 - 22.2.2026
Sovellus käyttää Tor-verkon `.onion` osoitteita vertaisviestintään, mutta uusien käyttäjien löytäminen (bootstrapping) vaatii ulkopuolisen kerroksen.
- **Nostr Relays**: Käytämme kaikille avoimia Nostr relay -palvelimia (kuten `wss://relay.damus.io` ja `wss://nos.lol`) julkistaaksemme ja löytääksemme muiden käyttäjien `.onion` osoitteita.
- **Sybil-suojaus (NIP-13 PoW)**: Nostr on täysin avoin ja kuka tahansa voi luoda loputtomasti feikki-avaimia. Tämän estämiseksi vaadimme kryptografisen Proof-of-Workin (Työtodiste, NIP-13). 
  - Kun puhelin julkaisee osoitteensa, sen täytyy laskea SHA-256 tiivistettä kunnes saavutetaan *Difficulty 22* (ID:n pitää alkaa ainakin viidellä nollalla ja parilla bitillä). Tämä on tarkoituksella hidasta (kestää useamman minuutin puhelimella), mikä tekee massiivisesta spammaamisesta mahdotonta yhdelle hyökkääjälle.
  - Vain viestit, joiden PoW-vaatimus täyttyy, hyväksytään muilta.
- **Tallennetut Tapahtumat ja Expiration (NIP-01 & NIP-40)**: Käytämme Nostr *Replaceable* Kind `10337`.  Nämä tallentuvat Relay-palvelimille. Jotta roskan määrä pysyy kurissa, lisäämme eventtiin NIP-40 `"expiration"` -tagin (7 päivää eteenpäin), jolloin Relay voi siivota sen automaattisesti pois.
- **Rate-Limiting & Ajoitus**: Puhelin julkaisee ja louhii PoW:n vain **kerran 24 tunnissa** (SharedPreferences), paitsi jos `.onion`-osoite muuttuu. `publishOurOnion()` odottaa 20 s ennen raskasta PoW-louhintaa (alkukättelyt ehtivät ensin) ja kutsuu `markPublished()` vasta kun releeltä saadaan NIP-01 `["OK", ..., true, ...]` -kuittaus. Oma sipuliosoite suodatetaan pois löydetyistä tuloksista.
- **TODO (Tietosuoja — Nostr kulkee vielä Torin ohi)**: `NostrBootstrapManager.kt` käyttää tällä hetkellä tavallista `OkHttpClient`:iä ilman SOCKS-proxya, jolloin Nostr-palvelimet näkevät puhelimen IP-osoitteen yhdessä julkaistun `.onion`-osoitteen kanssa. Korjataan erikseen reitittämällä Nostr-yhteydet Torin dynaamisen SOCKS-portin kautta (ja estämällä paikallinen DNS-vuoto kuten `TorTileDownloader`issa), kunhan kahden puhelimen `.onion`-viestintä on ensin varmistettu toimivaksi.

## 15. App Updates: Nostr + P2P Signed APK Seeding 🔄🛡️
Sovelluksen päivitykset levitetään P2P-verkossa ilman sovelluskauppaa tai pysyvää palvelinta: puhelimet jakavat itse asennettua APK-tiedostoaan (`applicationInfo.sourceDir` / `base.apk`) suoraan toisilleen Tor-verkon yli.
- **Nostr-julkaisu (Kind `10338`)**: Kehittäjä julkaisee allekirjoitetun manifestin Nostr-releihin (tagit `samizdat_ver`, `samizdat_sha256`, `samizdat_size`, `samizdat_sig`, `samizdat_sig2`, sekä valinnainen `samizdat_url`). Ei vaadi PoW-louhintaa, koska luottamus perustuu sisäänrakennettuun kehittäjän julkiseen RSA-avaimeen.
- **Kryptografia**: Kaikki päivitysallekirjoitukset ja identiteettiavaimet käyttävät **SHA256withRSA**:ta, eivät Ed25519:ää (Android API < 33 KeyStore ei tue Ed25519:ää natiivisti).
- **Passive Discovery & Gossip**: Status-viesteissä kulkee `"app_v"`. Kun vertainen, jolla on välimuistissa vahvistettu manifesti (`cachedUpdateJson`), vastaanottaa pienemmän `"app_v"`-version, se lähettää manifestin automaattisesti (rajoitettu max 1 kerta / vertainen / tunti).
- **Kolmen lukon turvamalli (`UpdateManifest.kt` + `UpdateManager.kt`)**:
  1. **Lukko 1 (Ennen latausta)**: RSA-allekirjoitukset `sig2` (`UPDATE2:<pkg>:<ver>:<size>:<sha256>`) ja `sig` (`UPDATE:<ver>:<sha256>`) tarkistetaan ennen ensimmäistäkään ladattua tai välitettyä tavua.
  2. **Lukko 2 (Latauksen aikana ja jälkeen)**: Lataus katkeaa heti, jos koko ylittää allekirjoitetun `size`-arvon (ja ylärajan `MAX_APK_DOWNLOAD_BYTES` = 100 Mt), ja valmiin tiedoston SHA-256:n on täsmättävä `sha256`-kenttään. Tukee jatkolatausta (`HTTP Range`).
  3. **Lukko 3 (Ennen asennusta)**: Ladatun APK:n allekirjoitusvarmenteen (`getPackageArchiveInfo`) on täsmättävä asennetun sovelluksen varmenteeseen, ja lataus sekä asennus vaativat aina käyttäjän kuittauksen UI-dialogissa.
- **Itsenäinen P2P-jakaja (`ApkSeeder.kt` + `ConnectionManager.kt`)**:
  - Kun puhelin saa oman asennetun versionsa allekirjoitetun manifestin ja oman `base.apk`:n koko + SHA-256 täsmäävät, se alkaa jakaa APK:ta osoitteestaan `http://<oma-onion>.onion/update_v<ver>.apk`.
  - **DoS-rajoitukset (globaalit per jakaja, koska Tor-liikenne tulee `127.0.0.1`:stä)**: max **1 samanaikainen lähetys**, max **4 lähetystä / h**, max **150 Mt / 24 h**, max **20 min / lähetys**, ja oletuksena vain **Wi-Fi / mittaamaton verkko + akku >= 30 % (tai laturissa)**.
- **Julkaisuprosessi (Workflow)**:
  1. Nosta `versionCode` (ja `versionName`) tiedostossa `app/build.gradle.kts`.
  2. Käännä APK: `./gradlew assembleRelease`
  3. Aja `~/projects/samizdat/publish_update.sh` (laskee SHA-256:n ja koon, allekirjoittaa `private_rsa.pem`:llä, julkaisee Kind `10338` -manifestin Nostriin ja kopioi APK:n `~/samizdat_updates/update_v<N>.apk`).
  4. Asenna APK omalle puhelimelle → puhelin varmistaa oman APK:nsa ja aloittaa P2P-jaon Torin yli (läppärin HTTP-palvelinta ei tarvita; valinnainen vain legacy v18 -asiakkaille).
- **Release Keystore (`keystore.properties`)**: `app/build.gradle.kts` käyttää omaa release-keystorea jos `keystore.properties` löytyy juuresta, muuten varalla debug-avainta. Säilytä `private_rsa.pem` ja release-keystore erillään ja varmuuskopioituina!

## 16. Tietoturvaparannukset v2 (Security Hardening Phase 2) - 3.3.2026 🔒
Toteutettiin laaja koodikatselmointi ja korjattiin löydetyt kriittiset ja korkean tason haavoittuvuudet:
- **Nostr Schnorr Signature Verification (Kriittinen)**: `NostrBootstrapManager` vahvistaa nyt Nostr-eventtien `sig`-kentän (BIP340 Schnorr) käyttämällä eventin ID:tä, estäen väärennettyjen onion-osoitteiden ja päivitystapahumien syöttämisen. ID:n uudelleenlaskenta on vain varoitus (yhteensopivuus mm. `nak`-työkalun kanssa).
- **Android Backup poistettu (Kriittinen)**: `AndroidManifest.xml` -> `allowBackup="false"`. Estää Torin `.onion` yksityisavaimen ja Room-tietokannan viennin ADB-backupilla.
- **SOCKS Port Null-Safety (Korkea)**: Poistettu kaikki 8+ tapausta `?: 9050` hardkoodauksesta. Yhteyksiä ei yritetä muodostaa ennen kuin Tor-palvelu on antanut dynaamisen SOCKS-portin.
- **Strict Tor Routing (Korkea)**: `ConnectionManager.sendMessage` hyväksyy vain `.onion` päätteisiä osoitteita ja verkkovirheet heittävät `SecurityException`. Estää suorat TCP IP -yhteydet ja IP:n paljastumisen.
- **APK Download Size Limit (Korkea)**: `UpdateManager` rajoittaa päivitystiedostot enintään 100 MB kokoiseksi (`MAX_APK_DOWNLOAD_BYTES`), suojaten laitteen tallennustilan täyttämiseltä.

## 17. JUURISYY: Paikallinen palvelin kuunteli IPv6-osoitetta `::1` (v0.7.3-alpha / Build 22) - 18.11.2026 🎯
- **Oire**: Kaikki `.onion`-yhteydet (viestit, ping, kättely) epäonnistuivat molempiin suuntiin virheellä `SOCKS server general failure`.
- **Diagnoosi (läppärin Tor 0.4.9.11, `SocksPort ... ExtendedErrors`, `Log [rend]info [app]info`)**: `curl --socks5-hostname 127.0.0.1:19050 http://<puhelimen-onion>/` → HS-kuvaus löytyi, INTRODUCE_ACK = ack, RENDEZVOUS2 saatiin, BEGIN lähetettiin portille 80 → puhelin vastasi heti `END (misc error)` ennen CONNECTED-vastausta. Tor-verkko ja onion-palvelu toimivat siis, mutta puhelimen Tor ei saanut yhteyttä paikalliseen kohteeseen `127.0.0.1:12345`.
- **Juurisyy**: Commit `b8b26f4` (3.3.2026, "Bind ServerSocket to localhost loopback only") vaihtoi `ServerSocket(port)` → `ServerSocket(port, 50, InetAddress.getLoopbackAddress())`. **Androidilla `getLoopbackAddress()` palauttaa AINA IPv6-osoitteen `::1`** (AOSP: *"Android-changed: Always returns IPv6 loopback address in Android"*). Palvelin kuunteli siis vain `[::1]:12345`, mutta `ADD_ONION Port=80,127.0.0.1:12345` ohjaa IPv4-osoitteeseen → yhteys evätään → Tor lähettää asiakkaalle `END misc` → asiakkaan Java näyttää `SOCKS server general failure`. Viestintä on ollut rikki maaliskuusta 2026 lähtien.
- **Korjaus**: `ConnectionManager.startListening()` sitoo nyt eksplisiittisesti `InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1))`. Vain localhost (ei LAN), kuten ennenkin.
- **SÄÄNTÖ**: **ÄLÄ** käytä `InetAddress.getLoopbackAddress()` Androidilla, jos toinen osapuoli (Tor) käyttää IPv4-osoitetta `127.0.0.1`. Palvelimen bind-osoitteen ja `SamizdatTorManager`in `ADD_ONION`-kohdeosoitteen on täsmättävä.
- **Lokit**: Debug Logsin jokaisella rivillä on nyt aikaleima (`HH:mm:ss.SSS`), lista on rajattu 1500 riviin, palvelimen käynnistys/virhe (`SERVER: Listening on 127.0.0.1:12345` / `SERVER ERROR`), saapuvat HTTP-pyynnöt, SOCKS-portti ja oma onion-osoite kirjataan, ja `DHT SYNC` kirjataan vain kun synkronoitava alue muuttuu. "Share Logs" jakaa koko lokin + otsikon (versio, onion, Tor-tila).
- **Testausvinkki (yhdellä puhelimella)**: Käynnistä läppärillä erillinen Tor (`SocksPort 127.0.0.1:19050 ExtendedErrors`) ja aja `curl -v --socks5-hostname 127.0.0.1:19050 http://<puhelimen-onion>/`. Toimiva puhelin vastaa HTTP-vastauksella (ApkSeeder), ja puhelimen Debug Logsiin tulee rivi `SERVER: Incoming HTTP GET ...`. SOCKS-koodit: `0xF0` kuvausta ei löydy, `0xF2` esittely epäonnistui (vanha kuvaus / palvelu ei käynnissä), `0x01` esim. paikallinen kohde ei vastaa.
- **Huom. vanhentuneet kuvaukset**: Kun sovellus käynnistetään uudelleen, toisten Tor-asiakkaiden välimuistissa voi olla vanha HS-kuvaus vanhoilla esittelypisteillä (`INTRODUCE_ACK nack, reason 1`). Tor hakee uuden kuvauksen automaattisesti, ja `ConnectionManager`in kolme uudelleenyritystä (v21) kattavat tämän.

---
- Repository: https://github.com/turhanjuoksija/Samizdat
- Releases: https://github.com/turhanjuoksija/Samizdat/releases
- *Päivitetty viimeksi: 2026-11-21*
