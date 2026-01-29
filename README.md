# Samizdat Android App

An Android application for Ed25519 key generation and local P2P discovery.

## Features
- **Key Generation**: Generate Ed25519 key pairs using Android Keystore (API 33+).
- **P2P Discovery (NSD)**: Automatically discover other devices on the same Wi-Fi.
- **QR Code Support**: 
    - Display your IP as a QR code.
    - Scan a friend's QR code to get their IP.
- **Manual Entry**: Fallback to manually type an IP address.

## How to Run
1. Open in Android Studio.
2. Connect your physical Android device.
3. Run the app.

## Testing Discovery (IMPORTANT)
Network discovery only works if devices are on the **same network**.
If you don't have the password for the local Wi-Fi:
1.  **Create a Hotspot**: On Phone A, go to **Settings > Network > Hotspot & Tethering** and turn on **Wi-Fi Hotspot**.
2.  **Connect**: Connect Phone B (and your laptop if debugging) to that Hotspot.
3.  **Launch App**: Open Samizdat on both phones.
4.  **Verify**: 
    - You should see "Discovered Peers" list populate.
    - You can try the "Scan Peer QR" button to scan the QR code from the other phone.

## Troubleshooting
- **No Peers Found?**: firewall/router configuration might block mDNS. Using a phone Hotspot is the most reliable way to test.
- **Camera Permission**: The app will ask for permission when you click "Scan Peer QR".
