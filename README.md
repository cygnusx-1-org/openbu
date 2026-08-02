# Openbu
<p align="center">
<img src="app/src/main/ic_launcher-playstore.png" width="180">
</p>

<hr style="display: inline-block; width: 100%; border: 1px dotted #ff00cc;">

<p align="center" style="margin-top: -2em;">
  <a href="https://discord.gg/vDuSpJEDrW">
    <picture>
      <source height="24px" media="(prefers-color-scheme: dark)" srcset="/assets/icons/Discord.png" />
      <img height="24px" src="/assets/icons/Discord.png" />
    </picture>
  </a>
</p>

Openbu is a open source [Kotlin](https://en.wikipedia.org/wiki/Kotlin) based [Android](https://en.wikipedia.org/wiki/Android_(operating_system)) app written for [Bambu Lab](https://bambulab.com/en-us) printers in [Developer Mode](https://help.simplyprint.io/en/article/bambu-lab-lan-only-mode-and-developer-mode-how-to-enable-xa0hch/).

## Backstory(aka Why not Handy or Lanbu?)
I own a [Bambu Lab](https://bambulab.com/en-us) [P1S](https://bambulab.com/en-us/p1). I would have stuck with the older pre-1.08 firmware, but then I purchased a [AMS HT](https://us.store.bambulab.com/products/ams-ht?from=home_web_top_navigation) which needed 1.08+ firmware to be properly supported. I also wanted [OrcaSlicer](https://github.com/OrcaSlicer/OrcaSlicer) support under [Linux](https://en.wikipedia.org/wiki/Linux), and there is no [Bambu Connect](https://wiki.bambulab.com/en/software/bambu-connect) for [Linux](https://en.wikipedia.org/wiki/Linux). It has been "Under Development" for about a year.

I quickly found [Lanbu](https://play.google.com/store/apps/details?id=com.Glowbeast.LanBu&hl=en_US), but I have a few issues with it. It isn't open source, it isn't especially pretty, the author locked the video feature behind a paywall, and it doesn't auto-detect the printers on the network.

## Status
This is a new project, and has stated above I own a [P1S](https://bambulab.com/en-us/p1) and a [AMS HT](https://us.store.bambulab.com/products/ams-ht). This makes it harder to test and support A1, P2, X1, and H2 series printers.

I workaround this with a program that mocks the [MQTT](https://en.wikipedia.org/wiki/MQTT) output, and a MJPEG stream for A1 and P1 series printers. I use another solution to simulate the RTSPS video stream of other models.

> [!NOTE]
> This means user testing of every possible configuration would be very helpful, and then open [issues](https://github.com/cygnusx-1-org/openbu/issues).

I have currently tested this on a [Google Pixel 8 Pro](https://www.gsmarena.com/google_pixel_8_pro-12545.php) running [Android 16](https://en.wikipedia.org/wiki/Android_16). The current minimum [Android](https://en.wikipedia.org/wiki/Android_(operating_system)) version is [8/Oreo](https://en.wikipedia.org/wiki/Android_Oreo).

I am strongly considering adding this to the [Google Play Store](https://play.google.com/store/apps?hl=en_US), but we will see. They sometimes make that a challenge. I already have one app in the store.

## Features
* Auto-detects printers, and auto fills in the ip address and serial number. Hence only requires the access code.
* Saving the printer connection settings by default
* Bed and nozzle temperature control
* Fan speed control
* Allows the user to add an external [RTSP](https://en.wikipedia.org/wiki/Real-Time_Streaming_Protocol) stream to the dashboard by entering a [RTSP](https://en.wikipedia.org/wiki/Real-Time_Streaming_Protocol) URL, and with pinch to zoom
* Supports the A1 and P1 series video stream based on JPEGs with pinch to zoom.
* Support [RTSP](https://en.wikipedia.org/wiki/Real-Time_Streaming_Protocol) streams from non-P1 series internal cameras with pinch to zoom
* Supports toggling of the chamber light
* [AMS HT](https://us.store.bambulab.com/products/ams-ht?from=home_web_top_navigation), [AMS](https://us.store.bambulab.com/products/ams-multicolor-printing?from=home_web_top_navigation), and [AMS 2 Pro](https://us.store.bambulab.com/products/ams-2-pro?from=home_web_top_navigation)
  - Knows the correct number of trays per model
  - Shows temperature, humidity, filament types, and filament colors
  - Assigning filament types and filament colors per tray
* Showing filament type and filament color of the External Spool
* Assigning filament types and filament for the External Spool
* Shows job status including layers, time left, estimated time, job name, and percentage of job done
* Shows status of the nozzle and bed
* Shows status of part fan, aux fan, and chamber fan depending on what the printer model has
* Setting print speed
* Pause/Resume and Stop
* File management via `File Manager` which uses `FTPS`
* Skip Objects support
* Remote access via [openbu-relay](/helm/openbu-relay)

[Feature requests](https://github.com/cygnusx-1-org/openbu/issues) are welcome.

## Building
The only prerequisites are the [Android SDK](https://developer.android.com/studio) and a network connection. The Gradle wrapper fetches Gradle itself, and `gradle/gradle-daemon-jvm.properties` makes it auto-provision the required [JDK](https://en.wikipedia.org/wiki/Java_Development_Kit) through [Foojay](https://foojay.io/), so no local JDK install or `JAVA_HOME` setup is needed.

Point the build at your SDK, either by opening the project in [Android Studio](https://developer.android.com/studio) (which writes `local.properties` for you) or by setting `ANDROID_HOME` yourself:

```
git clone https://github.com/cygnusx-1-org/openbu.git
cd openbu
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew assembleDirectDebug
```

That produces per-[ABI](https://developer.android.com/ndk/guides/abis) APKs under `app/build/outputs/apk/direct/debug/`. Use `./gradlew installDirectDebug` to build and install onto a connected device. Debug builds get the `.debug` application id suffix, so they install alongside a release or Play copy of the app.

There are two product flavors: `direct` for sideloaded APKs and `play` for [Play Store](https://play.google.com/store/apps?hl=en_US) bundles. Swap the flavor into any task name, e.g. `assemblePlayDebug` or `bundlePlayRelease`.

> [!NOTE]
> Signing credentials live in `keystore.properties`, which is deliberately not in the repo. Without it, debug builds are unaffected and release builds simply come out unsigned. To sign your own release builds, copy [keystore.properties.example](keystore.properties.example) to `keystore.properties` and fill it in.

## Screenshots
<p align="center">
<img src="screenshots/actively-printing.png" width="180">
</p>

[All screenshots](https://github.com/cygnusx-1-org/openbu/tree/master/screenshots)

## Donations
[<img src="./assets/badges/buymeacoffee_badge.png"
    alt="Buy me a coffee"
    height="80">](https://buymeacoffee.com/edgan)

Any monetary donation would be appreciated. I am open to hardware donations.
