# Samizdat Development & Versioning Notes 🛡️

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
- **Grids from Route**: `RadioGridUtils.getRouteGridsFromPolyline()` muuntaa tieuran listaksi Grid-ID:itä, joihin kuljettajan ilmoitus propagoidaan.

## 7. .onion Osoitteiden Käsittely (`OnionUtils`) 🌐
Tor v3 -osoitteet ovat 56 merkkiä pitkiä. Sovellus vaatii `.onion` päätteen, jotta Android ja Tor osaavat reitittää viestit oikein.
- **Utility**: Käytä `OnionUtils.ensureOnionSuffix(address)` aina kun tallennat tai lähetät viestejä. 

## 8. Tietokanta (Room) 🗄️
- Käytämme tällä hetkellä `fallbackToDestructiveMigration()` asetusta.
- **Varoitus**: Jokainen `version` numeron korotus `AppDatabase.kt` tiedostossa **PYYHKII KAIKKI TIEDOT**.

## 9. Verkkoviestintä & Aikakatkaisut ⏳
- **Timeout**: `ConnectionManager` käyttää 30 sekunnin timeoutia Tor-yhteyksille.
- **Background Sync**: DHT-haku ja Status Broadcast tapahtuvat taustasäikeissä (`viewModelScope`) jumiutumisen välttämiseksi.

## 11. Tekninen ympäristö & Kääntäminen (Java 17) ☕
- **Java versio**: Android Gradle Plugin 8.2+ ja Room 2.7 vaativat **Java 17** kääntäjän (JDK 17). 
- **Ongelmat**: Jos saat virheen `Android Gradle plugin requires Java 17 to run. You are currently using Java 11.`, päivitä `JAVA_HOME` tai tarkista IDE:n Gradle-asetukset.
- **Gradle JVM Args**: Android-kehitinympäristössä `kapt` ja `compose` saattavat vaatia enemmän muistia. `gradle.properties` tiedostossa on `org.gradle.jvmargs=-Xmx2048m`, jotta kääntäminen ei kaadu muistiin (OutOfMemoryError).

---
*Päivitetty viimeksi: 2026-01-24 (Map UI & Role UX)*

## 12. Kartan ja Käyttöliittymän Päivitykset (UI/UX) - 24.1.2026 🗺️
- **Undo-toiminto**: Korvattu kaksi erillistä poistonappia yhdellä `Undo (↩️)` -painikkeella, joka poistaa viimeisimmän reittipisteen.
- **Reittipisteet**: Käytämme nyt numeroituja ("1", "2"...) markkereita emoji-kokeilujen sijaan selkeyden vuoksi.
- **Rooli ja Ikonit**: Aloituspisteen ("Start") ikoni ja väri muuttuvat automaattisesti roolin mukaan (🚗/🔴 Driver, 🙋/🟢 Passenger).
- **Pakotettu Roolivalinta**: Karttaa ei voi käyttää reitin luomiseen ennen kuin rooli on valittu (Overlay aukeaa automaattisesti).
- **Broadcast Logiikka**: Broadcast menee automaattisesti pois päältä (OFF), jos reittiä muokataan (Undo tai uusi piste), jotta väärää tietoa ei lähetetä.
- **Roolinvaihto bugi korjattu**: Roolin vaihtaminen tyhjentää nyt varmuudella _kaikki_ vanhat reittitiedot, estäen haamuviivojen jäämisen kartalle.
- **Mukautetut Karttamerkit**: Toteutettu emoji-pohjaiset pyöreät ikonit aloituspisteelle (🏠/🚗/🙋) ja numeroitavat kultaiset pallot reittipistille (1, 2, 3...).
- **Suorat viivat poistettu Drivereilta**: Syaaniväriset "varaviivat" eivät enää näy Driver-roolissa, vain Passenger-roolissa jos tiepohjaista reittiä ei ole laskettu.
