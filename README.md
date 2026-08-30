# Lutty

Apply `.cube` LUTs to log video on Android.

Built because the Oppo Find X8 Ultra's own editor only offers LUT grading on footage its own
camera shot — anything imported gets the generic video controls instead.

## What it does

- Import a clip, stack up to two `.cube` LUTs, adjust strength per LUT
- Exposure and white balance applied in scene-linear before the LUTs; contrast and saturation
  after, in the output space, so each control behaves the way you expect
- Converts between log formats, so LUTs built for one camera apply correctly to another
- Detects the clip's log format automatically
- Live preview that runs the identical shader as the export
- Bakes the whole grade into a single `.cube` for reuse on a desktop
- Exports at full resolution into the gallery

## Colour management

Transfer functions and gamut matrices come from primary sources and are pinned by unit tests
against values cross-checked with `colour-science`:

- **OPPO O-Log** — OPPO O-Log White Paper V1, sections 3.1, 3.2 and 4
- **Apple Log** — constants as implemented in OpenColorIO's `AppleCameras.cpp`
- **BT.2020 → Apple Wide Gamut** — composed via ACES AP0 under Bradford, using the matrix
  ratified in OpenColorIO-Config-ACES issue 163

`tools/verify_color.py` reproduces the derivation and the cross-checks.

## Known device limitation

Blackmagic Camera records Apple Log as HEVC **4:2:2** (Rext). The Snapdragon decoder in the
Find X8 Ultra supports Main and Main10 (4:2:0) only, so those files cannot be decoded. Record
HEVC 4:2:0 10-bit instead — chroma subsampling does not affect how a LUT resolves.

O-Log is 4:2:0 and is unaffected.

## Build

Requires the Android SDK and a JDK 17 or newer.

```
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```
