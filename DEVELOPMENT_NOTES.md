# Samizdat Development & Versioning Notes 🛡️

## 🚨 Pending Verification (Requires 2-Device Testing) 📱📱
- [ ] **Map Visuals**: Confirm that passengers see "Car 🚗" icons for real-time driver locations and "Signal 📡" for grid-based offers.
- [ ] **Map Interactions**: Confirm that the "Accept ✅ / Decline ❌" dialog appears correctly on the map for passengers and triggers the correct flow.
- [ ] **Offer Deduplication**: Confirm that only one offer appears per driver on the map/list (latest wins).

Tämä tiedosto sisältää kriittisiä huomioita projektin riippuvuuksista ja pakkausasetuksista. Lue tämä ennen kuin päivität kirjastoja tai muutat Gradle-asetuksia!

## 1. Tor & Native Libraries (Packaging) 📦
Tor-integraatio (`kmp-tor`) on erittäin herkkä natiivikirjastojen suhteen.
- **Conflict**: Useat kirjastot saattavat yrittää sisällyttää `libtor.so` ja `libevent.so` tiedostoja.
- **Ratkaisu**: `build.gradle.kts` tiedostossa on `packaging` -> `resources` -> `pickFirsts` säännöt näille. Älä poista niitä.
- **Dependencies**: Käytämme `resource-exec-tor` versiota. **ÄLÄ** lisää `resource-noexec-tor` kirjastoa, sillä se aiheuttaa duplikaattilähteitä ja kääntämisvirheitä.

## 2. Kotlin Metadata & Metadata-konfliktit 🧩
Android Gradle Plugin (AGP) 8.x + Kotlin 1.9.x aiheuttavat usein "Duplicate archive copy" virheitä `META-INF` tiedostoissa.
- **Sääntö**: `META-INF/kotlin-stdlib-jdk*.kotlin_module` tiedostoille on asetettu `pickFirst`.
- **Sääntö**: `META-INF/INDEX.LIST` ja `DEPENDENCIES` on asetettu `excludes` listalle.

## 3. Tor Konfiguraatio (SOCKS Port) 🔌
Tor-runtime vaatii tarkan tavan asettaa SOCKS-portti.
- **Huomio**: Käytä aina `toPortEphemeral()` funktiota (yli 1024 portit), muuten Tor saattaa epäonnistua käynnistyksessä joillakin laitteilla.
- **Portti**: SOCKS-portti tulee aina lukea dynaamisesti `torManager.socksPort.value` kautta. **ÄLÄ** käytä hardkoodattua porttia 9050.

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
- **Java versio**: Android Gradle Plugin 8.2+ ja Room 2.7 vaativat **Java 17** kääntäjän (JDK 17).
- **Ongelmat**: Jos saat virheen `Android Gradle plugin requires Java 17 to run.`, varmista että Java 17 on asennettu (`sudo apt install openjdk-17-jdk` Linuxilla).
- **Konfiguraatio**: Jos IDE ei löydä Javaa, lisää `gradle.properties` tiedostoon rivi: `org.gradle.java.home=/usr/lib/jvm/java-17-openjdk-amd64` (polku voi vaihdella käyttöjärjestelmästä riippuen).
- **Gradle JVM Args**: Android-kehitinympäristössä `kapt` ja `compose` saattavat vaatia enemmän muistia. `gradle.properties` tiedostossa on `org.gradle.jvmargs=-Xmx2048m`, jotta kääntäminen ei kaadu muistiin (OutOfMemoryError).

---
*Päivitetty viimeksi: 2026-02-12 (Security Hardening)*

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
