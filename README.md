# Samizdat

A decentralized, serverless ride-sharing application for Android via Tor and Kademlia DHT.

## ⚠️ Alpha Release
**Samizdat is currently in strict alpha testing.**
- **Expect Bugs:** Features may not work perfectly yet.
- **Small Network:** There are currently very few users (mostly developers). You might not find rides immediately.
- **Experimental:** We are polishing the core features daily.

## Features
- **Tor Anonymity**: All traffic routed through Tor Hidden Services (v3 Onion addresses). No central servers.
- **Decentralized Matching**: Ride offers are stored and retrieved using a Kademlia Distributed Hash Table (DHT).
- **Future Ride Scheduling**: Plan rides up to a week ahead. Drivers and passengers can post departure times, discover each other, and start chatting before the trip.
- **Reputation (Vouches)**: Trust is built peer-to-peer. Vouch for reliable drivers/passengers — vouch counts are visible on ride offers.
- **Persistent Identity**: Your device is your server. No account creation required.
- **Bootstrap via QR**: Scan a friend's **Onion Address** QR code to join the network.

## How to Run

### Option A: Download APK (Recommended for Testers)
1. Download the latest APK from the [Releases Page](https://github.com/turhanjuoksija/Samizdat/releases).
2. Install it on your Android device.
3. Open the app and wait for Tor to bootstrap (100%).
4. **Share your Onion Address** with a friend to start connecting!

### Option B: Build from Source (For Developers)
1. Open the project in Android Studio.
2. Connect your physical Android device.
3. Run the app directly.

## How it works
1. **Start**: The app launches Tor and generates your unique v3 Onion address.
2. **Connect**: Share this address (via WhatsApp, Signal, or QR code) with a friend.
3. **Peer Up**: When they add you, you see each other on the map.
4. **Ride**: Create a ride offer. It's stored in the distributed network (DHT).
5. **Accept**: Your friend sees the offer and accepts it.

## Current Status
- [x] Tor bootstrap and v3 Onion address generation.
- [x] Kademlia DHT for peer discovery and ride offer storage.
- [x] Ride offer creation and acceptance.
- [x] Ride tracking on map.
- [x] Reputation system (Vouch-based, local calculation).
- [x] Future ride scheduling (up to 1 week, max 5 active intents).
- [ ] Secure software updates through the network.
- [ ] Geocast message system.
- [ ] Notification system.
- [ ] Chat-Ride linking (connect chat threads to specific ride offers).

**Note on Payments:** Samizdat is free software. There will be no centralized payment system.

## Experimental Features
The codebase contains traces of Wi-Fi (NSD) local discovery. These are currently secondary to the Tor-based implementation.
