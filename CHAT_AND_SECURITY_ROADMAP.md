# Samizdat — Chat & Security Roadmap 📝🛡️

Tähän tiedostoon on koottu sovitut jatkokehitysaiheet seuraavia sessioita varten (poimittavaksi yksi kerrallaan).

## ✅ Toteutettu (2026-11-22)
- **Täysruudun Chat-näkymä** (`MessagesScreen.kt`):
  - Yläpalkissa vasemmalle osoittava takaisin-nuoli (`←`), avatari, live-tila (`🟢 Online`), pikanappi *"Add to Contacts"* tuntemattomille `.onion`-keskusteluille sekä keskustelun poisto.
  - **Pysyvä asento välilehtien välillä:** Jos käyttäjä hyppii `Map`- ja `Chat`-välilehtien välillä, `Chat` aukeaa takaisin samaan keskusteluun, samaan vierityskohtaan ja säilyttää keskeneräisen viestiluonnoksen.
  - **Päivämääräerottimet & selkeät kuplat:** `Today` / `Yesterday` / `18 Nov` -erottimet, erottuvat omat/vastaanotetut kuplat, kellonajat kuplien sisällä, sekä `Failed • Tap to retry` epäonnistuneille viesteille.
  - Androidin takaisin-nappi sulkee ensin avoimen keskustelun takaisin keskustelulistaan.
- **Keskustelulistan parannukset:**
  - Älykkäät aikaleimat (`19:29` / `Yesterday` / `18 Nov` — ei enää hämäävää `19:29` vs `22:36` -järjestystä).
  - Hakupalkki (hakee kontaktin nimen, `.onion`-osoitteen ja kaikkien viestien sisällön perusteella).
  - Keskustelun poisto listasta varmistusdialogin kanssa.
- **Kontaktin poisto (`ContactsScreen.kt`):**
  - Varmistusdialogi ja oletuksena valittu valintaruutu `"Also delete chat history"`, jotta orpoja `.onion`-ketjuja ei jää vahingossa kummittelemaan.
- **Lokien tietosuoja (`PeersViewModel.kt`):**
  - Saapuvien chat-viestien tekstisisältöä ei enää kirjata `Debug Logs` -listaan (jotta *"Share Logs"* ei vuoda yksityisviestejä).

---

## 🔒 1. TÄRKEIN SEURAAVA TIETOTURVA-ASKEL: Viestien kryptografinen allekirjoitus
> **ÄLÄ UNOHDA TÄTÄ!** Sovittu ensisijaiseksi tietoturvaparannukseksi (ennen mahdollista sovellustason lisäsalausta).

- **Ongelma:** Tor v3 `.onion` salaa liikenteen matkalla, mutta saapuvassa JSON-viestissä (`v: 1`) kenttä `f_onion` uskotaan tällä hetkellä sellaisenaan ilman kryptografista todistusta. Kuka tahansa verkossa oleva voi lähettää viestin ja väittää JSON:ssa olevansa toinen kuski tai matkustaja (`f_onion` väärennettävissä).
- **Tavoite:** Allekirjoitetaan suorat viestit (chat, `ride_request`, `ride_accept`, `ride_decline`, `status`) lähettäjän avaimella niin, ettei kukaan voi esiintyä toisena käyttäjänä.
- **Suunnittelupisteet seuraavaan sessioon:**
  1. Käytetäänkö olemassa olevaa `CryptoUtils` RSA-avainta (TOFU / kiinnitys `Peer.fullPublicKey`:hin kättelyssä/QR-koodissa) vai Torin `.onion` Ed25519-avainta (sitoo identiteetin suoraan `.onion`-osoitteeseen ilman erillistä avaimenvaihtoa)?
     - *Huom:* Torin `filesDir/onion_v3_private_key` (64 tavun laajennettu Ed25519) sitoo allekirjoituksen matemaattisesti suoraan 56 merkin `.onion`-osoitteeseen — silloin vastaanottaja voi tarkistaa allekirjoituksen pelkän `.onion`-osoitteen perusteella ilman aiempaa kättelyä!
  2. Mitkä kentät allekirjoitetaan (vähintään `v`, `f_onion`, `type`, `t`, `p` + aikaleiman tuoreustarkistus replay-hyökkäyksiä vastaan).
  3. Taaksepäin yhteensopiva siirtymä (esim. aluksi merkintä UI:ssa / varoitus allekirjoittamattomasta viestistä, myöhemmin hylkäys).

---

## 🧹 2. Keskustelujen automaattinen vanheneminen ("maatuminen")
- Asetuksiin (`SettingsScreen.kt`) valinta vanhojen viestien automaattiselle siivoukselle (esim. `7 days` / `14 days` / `30 days` / `Never`, oletus esim. `14 days` tai `30 days`).
- Käynnistyksessä (ja ajoittain taustalla) `MessageDao.deleteMessagesOlderThan(cutoffMs)` poistaa vanhentuneet viestit automaattisesti.
- Sopii kimppakyytisovelluksen luonteeseen: vanhat osoitteet ja noutopaikat eivät jää puhelimiin ikuisesti.

---

## 📌 3. Keskustelujen kiinnitys (Pin) ja järjestysvalinnat
- Pidetään oletuksena **uusin viesti ylimpänä**, mutta lisätään mahdollisuus kiinnittää vakiokuskeja/tuttuja listan kärkeen (Pin 📌).
- Tarvittaessa kevyt suodatin/järjestysvalitsin (`All` / `Contacts` / `Unknown`).
- *Huom:* Jos lisätään sarakkeita Room-tietokantaan (`AppDatabase.kt`), kannattaa samalla harkita oikean Room `Migration`:in lisäämistä `fallbackToDestructiveMigration`:in rinnalle, jotta vanhat kontaktit ja viestit eivät pyyhkiydy päivityksessä (tai tallentaa kiinnitetyt `peerKey`:t aluksi `SharedPreferences`:iin ilman skeemamuutosta!).

---

## 🔔 4. Lukemattomat viestit -ilmaisin (Unread badges)
- Merkitään saapuvat viestit lukemattomiksi, kun kyseinen keskustelu ei ole auki.
- Näytetään lihavoitu esikatselu + lukemattomien määrä keskustelukortissa sekä pieni badge alavalikon `Chat`-välilehdessä.
- *Vinkki:* Voidaan toteuttaa ilman Room-skeemamuutosta tallentamalla `lastReadTimestamp` per `peerKey` `SharedPreferences`:iin!

---

## 🚗 5. Aktiivisen kyydin linkitys Chat-näkymään
- Kun käyttäjällä on aktiivinen kyyti tai tuleva `RideIntent` kyseisen vertaisen kanssa, näytetään chatin yläreunassa pieni tietopalkki (esim. kyydin tila + pikapainike `Show on Map`).

---

## 🌐 6. Nostr yli Torin SOCKS-proxyn (Tietosuoja)
- Jo kirjattu myös [DEVELOPMENT_NOTES.md](DEVELOPMENT_NOTES.md) §14:ään: reititetään `NostrBootstrapManager`:in WebSocket-yhteydet Torin dynaamisen SOCKS-portin kautta, jotta Nostr-releet eivät näe laitteen oikeaa IP-osoitetta yhdessä `.onion`-osoitteen kanssa.
