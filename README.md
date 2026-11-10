# AutoTube

Personal Android Auto app: YouTube on the left, a live map with Google Maps turn instructions on the right. Sideloaded, not for the Play Store.

## How it works

- Android Auto only gives one navigation app the big pane, so AutoTube registers as a navigation app and draws its own split screen there.
- Left: the YouTube mobile site in a WebView. Tap the car screen to control it. Car buttons: Back, Home, page up, page down.
- Right: an OpenStreetMap map (MapLibre, no API key) following your GPS, with Google Maps' current turn, street and ETA read from its navigation notification.
- Google Maps keeps routing and speaking guidance on your phone in the background.
- Driving lock: above about 9 km/h the video is covered and only audio plays. It comes back after about 3 seconds stopped.

## Build the APK (no Android Studio needed)

1. Create an empty GitHub repository and push this folder to `main`.
2. GitHub Actions builds it. Open the repo's **Releases** page on your phone and download `AutoTube.apk`.
3. Every push makes a new release that installs over the previous one (fixed signing key).

Or open the folder in Android Studio and press Run.

## Phone setup

1. Install `AutoTube.apk` (allow installs from your browser when asked).
2. Open AutoTube, tap **Allow location** and **Allow Google Maps turn info** (turn on AutoTube in Notification access).

## Android Auto setup

1. Android Auto settings: tap **Version** about 10 times to unlock developer mode.
2. Three-dot menu, **Developer settings**, turn on **Unknown sources**.
3. Reconnect to the car. AutoTube shows up in the launcher.
4. Start a route in Google Maps, then open AutoTube on the car screen.

## Sending videos

- In the YouTube app: Share, then AutoTube.
- Or type a search in AutoTube on the phone and tap **Play on car**.
- Google sign-in is usually blocked inside WebViews, so YouTube runs signed out.

## Known limits

- If Android Auto stops Google Maps' route when AutoTube opens, the turn card will say so. Fallback: keep Maps in the big pane and use AutoTube only when parked.
- The map shows your position, not the Maps route line.
- Moving detection uses phone GPS, so it can lag a second or two in tunnels.
