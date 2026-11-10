# AutoTube 2.0

Personal Android Auto app: full-screen YouTube on the car screen, controlled from your phone. Sideloaded, not for the Play Store.

## What it does

- **Car home screen:** your recently played videos as a grid of thumbnails. Tap one to play it.
- **Full-screen player:** the video fills the whole car screen. Tap once for large controls: back, rewind 10 s, play/pause, forward 10 s and a seek bar (tap or drag it, or drag sideways anywhere on the video). The controls hide after 4 seconds.
- **Skip ad:** a floating button appears bottom right as soon as YouTube allows skipping, even when the controls are hidden.
- **Start times:** links with a start time (`?t=95`, `&t=1m30s`) open at that point.
- **Background:** if you open Google Maps or another app on the car screen, AutoTube pauses. When you come back, it carries on from the same spot.
- **Browse:** search results from your phone open on the car as the YouTube mobile site, in dark mode. Tap a video and it goes full screen.
- **Phone app:** search box, Paste link, Car home, and your recent videos. In the YouTube app you can also tap Share and pick AutoTube.
- **Driving lock:** above about 9 km/h the screen shows "Paused for driving" and only audio plays. The video comes back about 3 seconds after you stop.

Version 2.0 removed the map and the Google Maps turn info, so AutoTube no longer needs notification access.

## Install or update (clean reinstall)

You need a Mac with Google's platform-tools folder, and the phone's USB cable.

1. **Get the new APK.** Open your AutoTube repo on GitHub, then Releases, and download `AutoTube.apk` from the newest build.
2. **Put two files in your platform-tools folder:** `AutoTube.apk` and `install.sh`. Replace any older copies.
3. **Plug in the phone and unlock it.** USB debugging must be on (Settings > System > Developer options).
4. **Open Terminal in that folder and run:**
   ```
   bash install.sh
   ```
5. **When the phone asks, tap Install.** If Play Protect warns you, tap More details, then Install anyway.
6. **Wait for "Done"**, then unplug and connect to the car.
7. **If AutoTube isn't in the car's app list:** on the phone, open Android Auto settings > Customize launcher and tick AutoTube.

The script removes the old version, installs the new one so Android Auto accepts it, grants location, opens the app once and restarts Android Auto. It deletes the APK from the phone's Downloads at the end.

**Run the script again** whenever you install a new build, or if AutoTube disappears from the car after an Android Auto update.

## Updating the code on GitHub

1. Unzip the new version.
2. In your repo, delete the old `app` folder, then copy in everything from the zip (`app`, `.github`, `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `install.sh`, `README.md`). Deleting the old `app` folder matters: leftover 1.0 files will break the build.
3. Commit and push to `main`. GitHub Actions builds the APK in a few minutes and publishes it under Releases.
4. If the build fails, open the failed run under Actions, copy the red error lines and send them to Claude.

## If the script can't be used: manual steps

Run these in the platform-tools folder, one at a time:

```
./adb uninstall com.vk.autotube
./adb push AutoTube.apk /sdcard/Download/AutoTube.apk
./adb shell "content query --uri content://media/external/file --projection _id --where \"_display_name='AutoTube.apk'\""
```

The last command prints something like `_id=1234`. Use that number here:

```
./adb shell am start -a android.intent.action.INSTALL_PACKAGE -t application/vnd.android.package-archive -d content://media/external/file/1234 --grant-read-uri-permission --es android.intent.extra.INSTALLER_PACKAGE_NAME com.android.vending --ez android.intent.extra.NOT_UNKNOWN_SOURCE true
```

Tap Install on the phone, then:

```
./adb shell pm grant com.vk.autotube android.permission.ACCESS_FINE_LOCATION
./adb shell am start -n com.vk.autotube/.MainActivity
./adb shell am force-stop com.google.android.projection.gearhead
```

## Known limits

- YouTube runs signed out. Google blocks sign-in inside embedded browsers.
- The car can't show a keyboard, so search from the phone.
- The full-screen view works by hiding the rest of the YouTube mobile page. If YouTube changes its site, the page can show through. Tell Claude and it can be adjusted.
- Moving detection uses the phone's GPS, so it can lag a second or two in tunnels.
