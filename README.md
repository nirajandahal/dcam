# DualView Camera

Records the same moment as a **9:16 vertical** and a **16:9 horizontal** video at the same
time, from one camera feed. Photos work the same way: one shutter press, two framings.

## How it works

The camera gives one frame. That frame is uploaded to the GPU once, then drawn several
times — once to the on-screen preview, and once into each video encoder with a different
crop. Because the frame never leaves video memory, recording two formats costs barely more
than recording one.

Both files are real **MP4** (H.264 video, AAC audio) written with `MediaMuxer`, so they have
correct durations, scrub properly, and drop straight into Google Photos.

## Building the APK

No Android Studio needed.

1. Push this folder to a GitHub repository.
2. Go to the **Actions** tab. The `Build debug APK` workflow runs on every push, or you can
   start it by hand with **Run workflow**.
3. When it finishes, open the run and download the `dualview-debug-apk` artifact.
4. Unzip it and copy `app-debug.apk` to your phone.
5. Tap it to install. Android will ask you to allow installing from unknown sources.

Requires Android 10 (API 29) or newer.

To build locally instead, open the folder in Android Studio and run, or use
`./gradlew assembleDebug` if you generate a Gradle wrapper first.

## What the app adapts to

Phones vary enormously in what their video encoder accepts, so the app asks rather than
assumes:

- **Frame size limits.** Many mid-range chips cap out around 1920x1088 and reject a tall
  1080x1920 frame. When that happens the app encodes a landscape buffer and writes rotation
  metadata, so the vertical video still plays upright instead of silently failing.
- **How many encoders can run at once.** If the chip allows only one, the app records a
  single format and says so on screen rather than producing a broken second file.
- **Camera stream sizes.** Sizes the sensor can't deliver at a usable frame rate are
  discarded. If a phone refuses the chosen stream entirely, the app steps down and retries.

Whatever it settles on is printed under the viewfinder, and **Settings → Camera
diagnostics** shows the full picture: encoder name, maximum frame size, concurrent encoder
count, the stream in use, and the exact resolution of each planned file.

## Resolution, honestly

Both crops come from one sensor frame, so they share its pixels. A 4:3 stream is used
because it gives the most height for the vertical crop and the most width for the
horizontal crop at once.

At **4K** on a 12-megapixel sensor that typically means a true 4K vertical file
(2160x3840) alongside a 1440p horizontal one — the 16:9 crop simply doesn't contain 3840
columns of real detail. The app will never upscale to make a number look better; the
diagnostics screen always shows what you actually got.

## Where files go

`Movies/DualView` and `Pictures/DualView`, through MediaStore, so they appear in your
gallery automatically. Nothing is uploaded anywhere.

## Worth testing on your device

- 4K in **Both** mode — the heaviest case, and the most likely to reveal an encoder limit.
- The vertical video's orientation. If it plays sideways, the encoder fallback path is
  active and the rotation metadata needs flipping.
- Front camera, especially whether the mirroring matches what you saw on screen.
- Pause and resume mid-recording, and taking a photo while recording.
- A long 4K recording, to see whether the phone thermally throttles.
