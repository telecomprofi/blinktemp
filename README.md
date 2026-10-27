# Blink Temp

Android app + home-screen widget showing the average temperature of your Blink Outdoor cameras
(all homes / sync modules on one account).

Also could send freezing/temp threshold alert if configured.

Separately - python script to include avg temp reading to your Oh my zsh cli prompt in MacOS.


<img src="https://github.com/telecomprofi/blinktemp/blob/main/img/BlinkTemp_01.jpg" width=30% height=30%> <img src="https://github.com/telecomprofi/blinktemp/blob/main/img/BlinkTemp_02.jpg" width=30% height=30%>

## Install on your phone

**Once, on the phone:** Settings → About phone → tap *Build number* 7 times →
back to Settings → System → Developer options → turn on **USB debugging**.
Plug in by USB and tap **Allow** on the "Allow USB debugging?" prompt.

**On the computer** (needs Android Studio installed and opened once, so the SDK exists):

- macOS / Linux: `bash run.sh`
- Windows: double-click `run.bat`

Or in Android Studio: *File → Open* this folder, wait for sync, pick your phone at the top, press ▶.

The first build downloads Gradle and libraries (a few minutes). Later builds are fast.

## Use

1. Tap **Sign in to Blink**, log in, enter the PIN Blink sends you.
2. In **Cameras**, tick *Use in average* for the cameras you want. "Ignore offline cameras" (on by
   default) also drops cameras Blink reports as offline, since their temperature is a stale last value.
3. Tick *Image refresh* on cameras that should get a new thumbnail on schedule, then pick the interval
   (15 min – once a day) and optionally an active-hours window under **Scheduled image refresh**.
4. Long-press the home screen → Widgets → **Blink Temp** (2×2, resizable) or
   **Blink Temp 1×1** (just the number; tap it to refresh).

The widget refreshes every 30 min; tap the number to refresh now, tap elsewhere to open the app.

## Temperature refresh

Battery cameras only report a new temperature when they wake. Tick *Temp refresh* on cameras and
pick an interval (default hourly): the app wakes them, waits ~30 s, then saves the fresh reading
to the history graph. It first tries a light status request; cameras that don't support it (and
everything, if you turn on *Wake with a photo*) get a snapshot instead.

## History

Every refresh saves each camera's reading (kept 7 days, stored on the phone only). The History
section charts the average over the past 24 h or 7 days, using the cameras currently ticked
*Average*; the dashed red line is the freeze-alert limit. The lowest and highest average of the
past 24 h (with times) are shown under the big number in the app and as ↓/↑ on the 2×2 widget. Can be turned off or cleared.

## Spike filter

Readings below -50 °C or above +70 °C, or that change by more than 10 °C per hour since the
camera's last accepted reading, are ignored: they don't count in the average, don't trigger
freeze alerts and don't show in the graph (old spikes already saved are filtered too). For up to
2 h the camera's last good value is used instead.

## Freeze alert

On by default: a notification when **any camera** drops below 0 °C (limit adjustable from
-10 to +10 °C). Untick *Alert* on a camera to leave it out (e.g. an outdoor one). Offline cameras
are skipped. Android asks for notification permission the first time.

## Notes

Uses Blink's unofficial API (same as blinkpy / Home Assistant). If Blink changes its login,
update the constants at the bottom of `BlinkApi.kt` from blinkpy's `helpers/constants.py`.

Some phones (Xiaomi, Huawei, Samsung "deep sleep") kill background work; if the widget stops
updating, set the app's battery usage to *Unrestricted*.
