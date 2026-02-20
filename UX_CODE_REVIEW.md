# Samizdat UX & Architecture Review
*(Updated with Passenger Pickup Flow)*

This document contains a design and architecture review of Samizdat from a user's perspective, focusing on map interactions, location tracking, and the ride offer/acceptance flow. These are recommendations to tackle in upcoming sessions to make the app feel "ready" and premium.

## 1. Location Tracking & Marker Movement
**Current State:**
- The app requests the user's location via `LocationServices.getFusedLocationProviderClient(context).getCurrentLocation(...)` only **once** upon permission grant.
- There is no continuous `requestLocationUpdates` listener running.
- **Answer to your question:** No, the driver marker is *not* really moving on the map automatically. It only updates if the user manually long-presses the map to set their location (`isSetLocationMode`).
- Further, the broadcast of location to peers happens every **30 seconds**. This means even if you tracked GPS continuously, the passenger would only see the driver's car "teleport" every 30 seconds.

**Recommendations for coming sessions:**
1. **Foreground Location Tracking Service**: Implement a proper Android `Service` or `LocationCallback` in `RouteManager` that requests location updates every few seconds while driving (`PRIORITY_HIGH_ACCURACY`).
2. **Dynamic UI Interpolation (Smooth Movement)**: For the phone screen experience, the map marker shouldn't just jump when a new GPS coordinate arrives. We can use an animation in `MapComposable` to smoothly slide the car icon 🚗 from its old coordinate to the new one over 1-2 seconds. This makes the tracking *feel* live.
3. **High-Frequency Tor Updates (Post-Acceptance)**: 30 seconds is okay for grid DHT offers to save bandwidth. But once a ride is *accepted*, the driver and passenger should switch to a high-frequency direct Tor connection (e.g., every 5-10 seconds) so the passenger can watch the driver approaching on the map.

## 2. ETAs and Routing (Passenger Perspective)
**Current State:**
- When a passenger looks at the Live Offers (`OffersScreen.kt`), they see the walking distance TO the pickup point, and walking distance FROM the dropoff point.
- **Answer to your question:** No, the passenger does not get an estimation of how long the car will take to arrive at the pickup point.

**Recommendations for coming sessions:**
1. **Driver ETA Calculation**: When displaying `OfferCard`, we should make a quick call to `RoutingService.getRoute` (or simple Haversine formula for a rough estimate) from the driver's `driverCurrentLat/Lon` to the passenger's pickup location. 
2. **Display ETA**: Show a badge saying "Arriving in ~5 mins" or "Driver is 4 km away". This is crucial for a passenger to decide if they should accept an offer.

## 3. The Passenger Pickup Core Flow
**Current State / The Problem:**
- You correctly identified a major UX gap: if a driver doesn't deviate from their route, **how does the passenger know exactly *where* to stand?**
- Currently, the app knows the passenger's max walking distance and calculates if the driver passes nearby (in `DhtManager.calculateWalkDistances`), but it never shows the passenger a precise coordinate like "Stand at this intersection."
- You mentioned a workaround where a passenger might spoof their GPS to a bus stop. We want to avoid making the user do this. The app should be easy and logical.

**Recommendations for coming sessions (The "No-Spoofing" Pickup Flow):**
1. **Show Driver's Route to Passenger:** When a passenger taps an `OfferCard` (or a green marker on the map), the map should immediately draw that specific driver's exact route (`Polyline`) using the `routePoints` attached to the DHT message.
2. **Propose a Pickup Point (`📍`)**: The app automatically finds the closest point on the driver's route to the passenger's current GPS location. It places a special "Suggested Pickup" marker there.
3. **Propose a Dropoff Point (`🏁`)**: It does the same for the passenger's destination along the driver's route.
4. **Passenger Confirmation:** The passenger looks at the map: *"Okay, the driver is passing through this intersection 400m away. That's a good place to jump on board."* The passenger can drag the `📍` marker along the route to a slightly better spot (like a proper bus stop) if they want, or just accept the suggested one.
5. **Send Request:** When the passenger clicks "Request Ride", the app sends the exact coordinates of that `📍` (Pickup) and `🏁` (Dropoff) to the driver, NOT just the passenger's current couch location.

## 4. Accepting a Ride (Driver Perspective)
**Current State:**
- When a driver receives a Ride Request, it shows up as a card (A, B, C...) with the passenger's nickname, reputation, and raw message text. 
- The driver can click the `🗺️` map button to see the passenger on the main map.
- The driver does *not* immediately see how long it will take to reach the passenger.
- **Design Core Principle**: Drivers **do not deviate** from their planned routes. Passengers are expected to be picked up along the route, for example at bus stops or main road junctions. Therefore, detour calculators are unnecessary.

**Recommendations for coming sessions:**
1. **ETA to Pickup Calculation**: When the driver receives a request, the request will now contain the exact `Pickup Point` the passenger selected. The app calculates the driving distance/time from the driver's current location *along their existing route* to that `Pickup Point`.
2. **Display ETA UI**: Put a clear indicator like "Pickup is 2.5 km away" or "Arriving at pickup in 5 mins" directly on the request card. This helps the driver know when to expect the passenger without switching tabs.

## 4. Chat & Context
**Current State:**
- When scheduling a Future Ride, users click "Chat" to send an inquiry. However, the Chat screen is just raw messages; it doesn't remember *which* future ride they are talking about (as noted in `DEVELOPMENT_NOTES.md`).

**Recommendations for coming sessions:**
1. **Chat Context Headers**: When opening a chat from an offer, pin a small card at the top of the `MessageDialog` showing the trip details ("Regarding trip: Thu 18:00") so both users know what is being discussed.
