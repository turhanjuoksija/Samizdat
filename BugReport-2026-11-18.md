# Samizdat Bug Report — 2026-11-18 (`v0.7.1-alpha` / Build 20)

Tämä raportti perustuu kenttätestin (`video/2026-11-16/`) valokuvien ja ruutukaappausten sekä lähdekoodin analyysiin. Raportti on jaettu itsenäisiin osatehtäviin (`BUG-1` … `BUG-8`), joihin voi viitata suoraan uusissa keskusteluissa (esim. *"Korjaa BugReport-2026-11-18.md kohta BUG-1"*).

---

## 1. Testitilanteen yhteenveto (`video/2026-11-16/`)

### Laitteet
- **Puhelin 1 (valokuvat `IMG_20261116_181847_HDR.jpg`, `181904`, `181947`)**:
  - Nickname: `Rebel_4558`
  - Onion: `d4wywqx6gzehs4m3...onion`
  - Yhteys: `4G+`, akku 36 %, `v0.7.1-alpha` (v20)
- **Puhelin 2 (ruutukaappaukset `Screenshot_20261116-182246.png` … `182710.png`)**:
  - Nickname: `Rebel_3149`
  - Onion: `l766wplbwjqo5xcplp36chnonzmefvliuv6xg5fta6apszuolee2zbid.onion`
  - Yhteys: `4G`, akku 59–61 %, `v0.7.1-alpha` (v20)

### Mikä toimi oikein kuvien perusteella
1. **Tor-käynnistys ja dynaaminen SOCKS-portti (`SOCKSPort auto`)**: Molemmat laitteet saivat Tor-piirit auki (`Tor Ready: 100%`).
2. **P2P-päivityksen allekirjoituksen tarkistus (`ApkSeeder` / `UpdateManifest`)**: Molemmissa laitteissa näkyy vihreä tila `Sharing v20 with peers (signed release verified)`.
3. **Nostr-löytäminen (`Kind 10337` + NIP-13 PoW 22 bittiä + BIP-340 Schnorr)**: Puhelin 1 löysi Puhelin 2:n julkaiseman `.onion`-osoitteen Nostr-releestä (`Nostr discovered peer: l766wplbwjqo5xcplp36chnonzmefvliuv6xg5fta6apszuolee2zbid.onion`).
4. **Kartta, reititys ja RadioGrid (`Screenshot_20261116-182710.png`)**: Driver-tila (`Broadcast LIVE`, `Seats: 3`), reitti (`Kasavuori -> Stop 1 -> Mankkaa`) ja reitin varrella olevat vihreät RadioGrid-solut piirtyivät oikein.

---

## 2. Havaitut bugit ja korjaussuunnitelmat

---

### BUG-1 (Kriittinen): `.onion`-yhteyden 30 s aikakatkaisu, puuttuva uudelleenyritys sekä `SOCKS server general failure`
**Tiedostot:**
- `app/src/main/java/com/example/samizdat/ConnectionManager.kt` (rivit 196–251)
- `app/src/main/java/com/example/samizdat/SamizdatTorManager.kt` (rivit 187–196, 288–293)

#### Havainto kuvista
- `IMG_20261116_181947_HDR.jpg`: Nostr löysi vastapuolen `.onion`-osoitteen, mutta välitön kättely epäonnistui: `HANDSHAKE FAILED: SOCKS server general failure`.
- `Screenshot_20261116-182246.png`: Puhelin 2 yritti lähettää viestejä ja kätellä `Rebel_4558`:n kanssa -> `ERROR sending to Rebel_4558: SOCKS server general failure` ja `HANDSHAKE FAILED: SOCKS server general failure`.
- `Screenshot_20261116-182517.png`: Chat-näkymässä viestit `hei` ja `hi` ovat tilassa `❌` ja `jjjjj` tilassa `⌛`.

#### Juurisyy
1. **Liian lyhyt connect-timeout (30 s) ilman uudelleenyritystä:**
   - `ConnectionManager.kt` (rivi 202) avaa `.onion`-soketin 30 sekunnin aikakatkaisulla:
     ```kotlin
     socket.connect(InetSocketAddress.createUnresolved(finalIp, targetPort), 30000)
     ```
   - Vertailun vuoksi `UpdateManager.kt` (rivi 132) ja `SegmentManager.kt` (rivi 161) käyttävät 120 sekunnin aikakatkaisua (`// Onion circuit building can take 60-90 s`).
   - Kun vastapuolen Hidden Service (HSv3) on juuri käynnistynyt tai laite on mobiiliverkossa (`4G`/`4G+`), HSDir-kuvauksen haku + Introduction Point -kättely + 6 hypyn Rendezvous-piiri kestää usein 35–75 sekuntia tai vaatii toisen yrityksen.
2. **Miksi virheilmoitus on geneerinen `SOCKS server general failure`:**
   - `libtor.so`:ssa (`stream_end_reason_to_socks5_response` / `connection_ap_handshake_socks_reply`) Tor muuntaa kaikki HSv3-virheet (esim. `SOCKS5_HS_REND_FAILED` = `0xF3`, `SOCKS5_HS_INTRO_FAILED` = `0xF2`, ` SOCKS5_HS_DESC_NOT_FOUND` = `0xF0`, tai piirin katkeaminen) tavalliseksi SOCKS5-koodiksi `0x01` (`General SOCKS server failure`), jos `SOCKSPort`-rivillä ei ole `ExtendedErrors`-lipuketta.
   - Androidin `java.net.SocksSocketImpl` heittää koodista `0x01` poikkeuksen `SocketException("SOCKS server general failure")`.
3. **Hidden Servicen `ADD_ONION` ilman `Detach`-lippua:**
   - `SamizdatTorManager.kt` (rivi 288) kutsuu `conn.addOnion(keyBlob, ports)` (2 parametria), jolloin `detach = false`. Jos kontrolliyhteys katkeaa hetkeksikään, Tor sulkee Hidden Servicen.

#### Korjaussuunnitelma
1. **`ConnectionManager.kt`**:
   - Nosta `.onion`-yhteyksien connect-timeout 30 sekunnista **90 sekuntiin** (`90_000` ms) ja aseta `socket.soTimeout = 60_000`.
   - Lisää `sendMessage()`-metodiin automaattinen **uudelleenyritys (esim. 2–3 yritystä, välissä 5 s viive)**, kun kohde on `.onion`-osoite ja yhteys kaatuu `SocketException`- tai `SocketTimeoutException`-virheeseen.
   - Jos `java.net.SocksSocketImpl` palauttaa laajennetun SOCKS5-virhekoodin (`0xF0`–`0xF7`, kun `ExtendedErrors` on päällä tai oma kevyt SOCKS5-kättely lukee vastauskoodin), muunna se selkeäksi lokiviestiksi (esim. `0xF0` = `HS descriptor not found yet`, `0xF2` = `HS introduction failed`, `0xF3` = `HS rendezvous failed`).
2. **`SamizdatTorManager.kt`**:
   - Kutsu `conn.addOnion(keyBlob, ports, false, true)` (`detach = true`), jotta Hidden Service pysyy elossa Tor-prosessissa kontrolliyhteyden tilasta riippumatta.

---

### BUG-2 (Kriittinen): Androidin Back-painike tuhoaa `MainActivity`:n ja sammuttaa Tor-palvelun
**Tiedostot:**
- `app/src/main/java/com/example/samizdat/MainActivity.kt` (rivit 172–379)
- `app/src/main/java/com/example/samizdat/PeersViewModel.kt` (rivit 1220–1224)

#### Havainto kuvista
- Kuvassa `Screenshot_20261116-182246.png` (klo **18:22:46**) Puhelin 2:n `Debug Logs` sisältää yhteydenottoyritykset `Rebel_4558`:lle.
- Kuvassa `Screenshot_20261116-182337.png` (klo **18:23:37**, 51 s myöhemmin) `Debug Logs` on tyhjentynyt kokonaan ja alkanut alusta aikaleimasta `1794846195325` (**klo 18:23:15**):
  - `1794846195325: Starting Nostr Bootstrap discovery...`
  - `1794846195325: Nostr: Already published recently, skipping.`
- Tämä todistaa, että klo 18:23:15 `PeersViewModel` tuhoutui ja luotiin uudelleen.

#### Juurisyy
- `MainActivity.kt`:n `MainScreen`-composablessa ei ole `BackHandler`-käsittelijää (vain `ContactsScreen`:n QR-skannerilla ja koko ruudun QR-dialogilla on omansa).
- Kun käyttäjä painaa Androidin Takaisin-painiketta tai -elettä millä tahansa välilehdellä (`Settings`, `Contacts`, `Chat`, `Grid`), Android kutsuu `MainActivity.finish()`.
- `finish()` kutsuu `PeersViewModel.onCleared()`, joka ajaa:
  ```kotlin
  repository.stopServer()
  torManager.stopTor()
  ```
- Kun sovellus avataan uudelleen, Tor käynnistyy samassa prosessissa uudestaan (`DEVELOPMENT_NOTES.md` §10: Torin in-process uudelleenkäynnistys on tunnetusti hauras), ja samalla vanhat HSv3 Introduction Point -piirit kuolevat, jolloin vastapuolen välimuistissa oleva HS-kuvaus osoittaa kuolleisiin pisteisiin -> vastapuoli saa `SOCKS server general failure`.

#### Korjaussuunnitelma
- Lisää `MainScreen`-composableen (`MainActivity.kt`) `BackHandler(enabled = true)`:
  - Jos `selectedTab != SamizdatTab.MAP`, vaihda välilehdeksi `selectedTab = SamizdatTab.MAP`.
  - Jos ollaan jo `SamizdatTab.MAP`-välilehdellä, kutsu `(context as? Activity)?.moveTaskToBack(true)`, jotta sovellus siirtyy taustalle tuhoamatta `MainActivity`:a, `PeersViewModel`:ia tai Tor-yhteyttä.

---

### BUG-3 (Korkea): `MainActivity.onCreate()` luo uudet `SamizdatTorManager`- ja `ConnectionManager`-instanssit konfiguraatiomuutoksissa
**Tiedosto:**
- `app/src/main/java/com/example/samizdat/MainActivity.kt` (rivit 88–107)

#### Juurisyy
- `MainActivity.onCreate()`-metodissa luodaan aina uudet oliot ennen kuin `ViewModelProvider` hakee `PeersViewModel`:n:
  ```kotlin
  val torManager = SamizdatTorManager(application)
  val connectionManager = ConnectionManager()
  ...
  TorTileDownloader.socksPortProvider = { torManager.socksPort }
  ...
  viewModel = ViewModelProvider(this, factory)[PeersViewModel::class.java]
  ```
- Kun `MainActivity` luodaan uudelleen (esim. näytön kääntö, teeman vaihto tai muistipaine), `ViewModelProvider` palauttaa **olemassa olevan** `PeersViewModel`:n (joka käyttää alkuperäistä `torManager`-instanssia), mutta `TorTileDownloader.socksPortProvider` ja `connectionManager.httpHandler` kytketään **uuteen, käynnistämättömään** `torManager`-instanssiin (jonka `socksPort` on `-1`). Tällöin karttatiilien lataus Torin yli lakkaa toimimasta.

#### Korjaussuunnitelma
- Siirrä `SamizdatTorManager`, `ConnectionManager`, `PeerRepository`, `UpdateManager`, `SegmentManager`, `ApkSeeder` sekä `TorTileDownloader.socksPortProvider`- ja `connectionManager.httpHandler`-kytkennät `PeersViewModelFactory.create()`-metodin sisään (joka ajetaan vain silloin, kun `PeersViewModel` oikeasti luodaan).
- Muista `DEVELOPMENT_NOTES.md` §10 sääntö: älä koskaan laita `org.torproject.jni.TorService`-tyyppiä Kotlin-luokan kentäksi tai metodiparametriksi.

---

### BUG-4 (Keskisuuri): `pingAllPeers()` nielee virheet hiljaisesti eikä kirjoita epäonnistumisesta `Debug Logs` -näkymään
**Tiedosto:**
- `app/src/main/java/com/example/samizdat/PeersViewModel.kt` (rivit 742–773)

#### Havainto kuvista
- Kuvassa `Screenshot_20261116-182337.png` näkyy kaksi peräkkäistä riviä:
  - `1794846205419: PING: Checking 1 known peer(s)...`
  - `1794846197128: PING: Checking 1 known peer(s)...`
- Kummankaan jälkeen ei tule minkäänlaista kuittausta tai virheilmoitusta `Debug Logs` -laatikkoon, vaikka ping ei mennyt läpi.

#### Juurisyy
- `PeersViewModel.pingAllPeers()` (rivit 759–771) lisää `_debugLogs`-listaan rivin vain onnistuessaan (`PONG`), mutta `catch (e: Exception)` -lohko kirjoittaa ainoastaan Androidin sisäiseen Logcatiin:
  ```kotlin
  } catch (e: Exception) {
      Log.d("PeersViewModel", "Ping failed for ${peer.nickname}: ${e.message}")
  }
  ```
- Lisäksi `pingAllPeers()` kutsuu suoraan `repository.sendToPeer(...)`, joka käyttää samaa 30 s aikakatkaisua kuin BUG-1.

#### Korjaussuunnitelma
- Lisää `PeersViewModel.pingAllPeers()`-metodin `catch`-lohkoon lokirivi:
  ```kotlin
  _debugLogs.add(0, "${System.currentTimeMillis()}: PING FAILED: ${peer.nickname} (${e.message})")
  ```
- Estä päällekkäiset ping-kierrokset samalle peerille (esim. atomisella lipulla tai `Mutex`:lla), jotta 30 s välein pyörivä taustasilmukka ei käynnistä uutta pingiä edellisen ollessa vielä kesken.

---

### BUG-5 (Keskisuuri): Nostr-julkaisu kuittaa onnistumisen 16 millisekunnissa ennen WebSocket-yhteyden avautumista ja ruuhkauttaa alkukättelyn
**Tiedostot:**
- `app/src/main/java/com/example/samizdat/NostrBootstrapManager.kt` (rivit 91–134, 193–243)
- `app/src/main/java/com/example/samizdat/SamizdatTorManager.kt` (rivi 319)

#### Havainto kuvista
- Kuvassa `IMG_20261116_181904_HDR.jpg`:
  - `1794845933617: Nostr: PoW Mined! Nonce: 4215993`
  - `1794845933618: Nostr: Broadcasting to Relays...`
  - `1794845933634: Nostr: Published successfully!`
- Aikaleimojen erotus `Broadcasting to Relays...` -> `Published successfully!` on vain **16 millisekuntia**, mikä on mahdotonta Torin yli avattavalle WebSocket-yhteydelle (`wss://relay.damus.io` jne.).
- Lisäksi `Debug Logs` täyttyy kymmenistä `Nostr: Mining... checked 5200000 hashes` -riveistä, jotka työntävät muut lokit pois näkyvistä.

#### Juurisyy
1. `NostrBootstrapManager.broadcastToRelays()` (rivit 193–210) käynnistää asynkroniset `client.newWebSocket(...)` -kutsut ja palaa välittömästi. Heti sen perään `publishOurOnion()` (rivit 126–127) kutsuu:
   ```kotlin
   markPublished(myOnionAddress)
   onStatusUpdate("Nostr: Published successfully!")
   ```
   Ennen kuin yksikään rele on avannut WebSocketia (`onOpen`) tai vastannut `["OK", ...]`. Jos lähetys epäonnistuu, `markPublished` estää uuden yrityksen 24 tunnin ajan (`PUBLISH_INTERVAL_MS`).
2. `SamizdatTorManager.kt` (rivi 319) käynnistää raskaan 22 bitin PoW-louhinnan (`Dispatchers.Default`, ~50 s 100 % CPU-kuorma) täsmälleen samalla hetkellä, kun Tor valmistuu ja `PeersViewModel` yrittää hakea Nostr-peerejä ja tehdä ensimmäiset `.onion`-kättelyt.

#### Korjaussuunnitelma
1. Kutsu `markPublished(myOnionAddress)` ja `onStatusUpdate("Nostr: Published to $relayUrl")` vasta WebSocketin `onMessage`-käsittelijässä, kun releeltä saadaan `["OK", "<event_id>", true, ...]` (tai vähintään `onOpen` + `ws.send(msg)` onnistuu).
2. Harvenna PoW-louhinnan lokitusta (esim. päivitä vain tilaviestiä tai lokita vain alussa ja lopussa `Debug Logs` -listaan, jotta `checked N hashes` ei täytä lokia).
3. Viivytä oman `.onion`-osoitteen Nostr-louhintaa/julkaisua esim. 15–30 sekuntia sen jälkeen kun alkudiscovery ja tunnettujen kontaktien alkukättely on käynnistetty.

---

### BUG-6 (UI / Matala): `My Contacts` -kortin tekstit ahtautuvat yhdelle vaakasuoralle riville
**Tiedosto:**
- `app/src/main/java/com/example/samizdat/ContactsScreen.kt` (rivit 58–103)

#### Havainto kuvista
- Kuvassa `Screenshot_20261116-182546.png` (`My Contacts`) kortissa lukee yhdellä ja samalla rivillä:
  `Rebel_4558 ☆   Onion: d4wywqx6gzehs4m3...`
- Jos peerillä on lisäksi etäisyystieto (`Dist: ...`), kaikki kolme elementtiä kilpailevat samasta vaakasuorasta rivistä.

#### Juurisyy
- `ContactsScreen.kt` (rivit 58–98) sijoittaa nimimerkin (`Row`), `Onion: ...`-tekstin (`Text`) ja `Dist: ...`-tekstin (`Text`) suoraan saman `Row(horizontalArrangement = Arrangement.SpaceBetween)`-komponentin lapsiksi ilman `Column`-rakennetta.

#### Korjaussuunnitelma
- Muuta kortin yläosa `Column`-rakenteeksi:
  - 1. rivi (`Row`): Online-pallo + Nickname + tähdet (`★`/`☆`) vasemmalla, `Dist: ...` oikealla.
  - 2. rivi (`Text`): `Onion: ${peer.onion.take(16)}...` omalla rivillään.

---

### BUG-7 (UI / Matala): Reittiviiva (`roadRoute`) piirretään kartalle kahteen kertaan päällekkäin
**Tiedosto:**
- `app/src/main/java/com/example/samizdat/MapComposable.kt` (rivit 363–371 ja 455–467)

#### Havainto kuvista ja koodista
- Kuvassa `Screenshot_20261116-182710.png` näkyy kartalla syaani reittiviiva, jonka alla on toinen sininen viiva.
- `MapComposable.kt`:n `AndroidView`-päivityslohkossa `roadRoute` lisätään `mapView.overlays`-listaan kahdesti:
  1. Rivit 363–371: `Color.BLUE`, `strokeWidth = 5f`
  2. Rivit 455–467: `Color.CYAN`, `strokeWidth = 8f`

#### Korjaussuunnitelma
- Poista ensimmäinen, duplikaatti `Polyline`-lohko (rivit 363–371) ja säilytä vain yksi selkeä reittiviivan piirto.

---

### BUG-8 (UI / Matala): `Debug Logs` -laatikon riviväli on liian suuri (`10.sp` teksti käyttää `24.sp` oletusriviväliä)
**Tiedosto:**
- `app/src/main/java/com/example/samizdat/SettingsScreen.kt` (rivit 500–517)

#### Havainto kuvista
- Kaikissa Settings-näkymän kuvissa (`IMG_20261116_181904_HDR.jpg`, `181947`, `Screenshot_20261116-182246.png`, `182337`) `Debug Logs` -laatikkoon mahtuu kerrallaan vain 3–4 lokiviestiä, koska pitkien rivien rivityksessä rivien väliin jää valtava tyhjä rako.

#### Juurisyy
- `SettingsScreen.kt` (rivi 513):
  ```kotlin
  Text(log, color = Color.Green, fontSize = 10.sp, modifier = Modifier.padding(bottom = 2.dp))
  ```
  Kun `lineHeight`-arvoa ei anneta, Material3:n `bodyLarge`-tyyli käyttää `24.sp` rivikorkeutta `10.sp` fontille.

#### Korjaussuunnitelma
- Aseta `lineHeight = 13.sp` `Debug Logs` -laatikon `Text`-komponentille.

---

## 3. Suositeltu korjausjärjestys uusiin keskusteluihin

1. **Keskustelu 1 — Yhteydet ja Tor (`BUG-1` + `BUG-4`)**:
   - `.onion` timeout 30s -> 90s + retry `ConnectionManager.kt`:ssa, `ADD_ONION` `detach = true` `SamizdatTorManager.kt`:ssa, sekä `PING FAILED` -lokitus `PeersViewModel.kt`:ssa.
2. **Keskustelu 2 — Elinkaari ja Back-nappi (`BUG-2` + `BUG-3`)**:
   - `BackHandler` `MainActivity.kt`:ssa (estää Torin sammumisen Back-napista) ja managerien luonnin siirto `PeersViewModelFactory`:yn.
3. **Keskustelu 3 — Nostr-luotettavuus (`BUG-5`)**:
   - WebSocketin `onOpen`/`OK`-kuittaus ennen `markPublished()`-kutsua ja PoW-louhinnan ajoitus/lokitus `NostrBootstrapManager.kt`:ssa.
4. **Keskustelu 4 — Käyttöliittymäviilaukset (`BUG-6` + `BUG-7` + `BUG-8`)**:
   - Kontaktikortin rivitys (`ContactsScreen.kt`), tuplareittiviivan poisto (`MapComposable.kt`) ja `Debug Logs` -riviväli (`SettingsScreen.kt`).

