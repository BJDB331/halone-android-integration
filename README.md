# Halone Android Apps plugin

A Halone plugin (`dev.halone.plugin.android`) that shows the phone's apps on
the glasses, each in its own window, and signal meters in the dock.

- **Android Apps** in the launcher: every launchable app, with a search field.
  A click opens the app (or focuses its window); right click offers
  *Add to Home* / *Remove from Home*, which lists it in Halone's launcher.
- **Each phone app** runs on a trusted virtual display created on its window's
  surface, as `scrcpy --new-display --no-vd-system-decorations` does. Input is
  injected into that display. Closing the window releases the display, so the
  task goes to the background on the phone and comes back intact next time.
  If the task leaves the display (opened on the phone, or finished), the
  window closes.
- **Back** in each app window's title bar sends Back to the app. Windows
  follow the orientation an app asks for, and past 1280 dp a larger window
  magnifies the app instead of giving it more room.
- **Dock status items**: Wi-Fi, mobile signal (only without Wi-Fi, with the
  network type, `5G`, `LTE`, when mobile data is on) and Bluetooth. Each
  opens its settings screen in a window on the glasses.
- **Recent** at the top of Android Apps: the last ten phone apps opened on
  the glasses, newest first, each with a still of its window. The service
  captures only its own virtual displays (never the phone's or the glasses'
  screen; protected content comes back empty), a few seconds after an app
  opens, every ten seconds while it shows, and as it closes. Stills stay in
  the app's private storage.
- **Phone screen off** for Halone's pad: with this plugin set up, the pad's
  screen-off button powers the phone's panel off instead of only dimming
  Halone's window. A leased session, switched by a watchdog process of its
  own: the panel comes back if Halone stops renewing it or dies, if the
  glasses' display goes, or if the service dies. Only Halone may ask
  (`PhoneScreenProvider`).
- **Setup screen** on the phone ("Halone Android Integration"): Shizuku and
  Bluetooth access, and a switch to hide its app drawer icon. Hidden, it opens
  from the app's page in Settings → Apps.

## Known limitations

- **No on-screen keyboard in phone apps.** Text fields in a phone app don't
  bring up a keyboard; type into them with a physical keyboard. Android only
  shows a keyboard for the top-focused display, and the app displays are
  created with `FLAG_STEAL_TOP_FOCUS_DISABLED` so that the mouse and the
  phone-as-touchpad keep controlling Halone. Without that flag the keyboard
  does appear, but focus moves between the app display and the phone, and
  input stops reaching Halone.

## Privilege

Displays and input injection need shell rights, from
[Shizuku](https://shizuku.rikka.app/) (adb or root mode) or Sui. Every hidden
API call lives in `privileged/DisplayService.kt`, the Shizuku user service.
Without access, an app window says what is missing; tapping it asks again.

## Build

Needs JDK 17. The plugin SDK, `dev.halone:plugin-sdk`, comes from Maven Central.

```
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am broadcast -a dev.halone.DEV -p dev.halone.app --es command plugin --es package dev.halone.plugin.android --ez enabled true
```

Open an app directly with
`--es command launch --es id dev.halone.plugin.android/<package>/<activity>`.
