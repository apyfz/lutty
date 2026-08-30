#!/usr/bin/env bash
#
# Converts Apple Log footage into something this phone can actually decode.
#
# Blackmagic Camera records Apple Log as HEVC 4:2:2, and Snapdragon decoders handle 4:2:0 only,
# so the file has to be re-chroma'd before it will open. Everything else is preserved: 10-bit
# depth, frame rate, the log values themselves, and the audio track are all untouched.
#
# Do NOT let the phone or a transfer app do this for you. Set Settings > Apps > Photos >
# Transfer to Mac or PC to "Keep Originals" first, or you get 8-bit H.264 at half the frame rate
# and the log image squeezed into ~125 luma levels, which bands badly under any conversion LUT.
#
# Usage:
#   tools/prep-clips.sh clip.MOV [more.MOV ...]     convert next to the originals
#   PUSH=1 tools/prep-clips.sh clip.MOV             convert and copy to the phone's camera roll

set -euo pipefail

command -v ffmpeg >/dev/null || { echo "ffmpeg is not installed" >&2; exit 1; }
[ $# -gt 0 ] || { sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; exit 1; }

for src in "$@"; do
  [ -f "$src" ] || { echo "skipping, not a file: $src" >&2; continue; }

  fmt=$(ffprobe -v error -select_streams v -show_entries stream=pix_fmt -of csv=p=0 "$src")
  if [ "$fmt" = "yuv420p10le" ]; then
    echo "already 4:2:0 10-bit, nothing to do: $(basename "$src")"
    continue
  fi
  if [ "$fmt" = "yuv420p" ]; then
    echo "WARNING: $(basename "$src") is 8-bit already — it was re-encoded somewhere in transit." >&2
    echo "         Converting will not put back the levels that were lost." >&2
  fi

  out="${src%.*}_420_10bit.mov"
  echo "converting $(basename "$src")  ($fmt -> yuv420p10le)"

  # Hardware encoder, high bitrate. Colour tags are carried across so the log profile is still
  # detected correctly on the other side.
  ffmpeg -v error -stats -i "$src" \
    -c:v hevc_videotoolbox -profile:v main10 -pix_fmt p010le -b:v 120M \
    -color_primaries bt2020 -colorspace bt2020nc -color_range tv \
    -tag:v hvc1 -c:a copy -y "$out"

  before=$(ffprobe -v error -select_streams v -show_entries stream=nb_frames -of csv=p=0 "$src" 2>/dev/null || echo "?")
  after=$(ffprobe -v error -select_streams v -show_entries stream=nb_frames -of csv=p=0 "$out" 2>/dev/null || echo "?")
  echo "  -> $(basename "$out")  frames $before -> $after"

  if [ "${PUSH:-0}" = "1" ]; then
    command -v adb >/dev/null || { echo "adb is not installed" >&2; exit 1; }
    adb push "$out" "/sdcard/DCIM/Camera/$(basename "$out")" >/dev/null
    adb shell "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
      -d file:///sdcard/DCIM/Camera/$(basename "$out")" >/dev/null 2>&1
    echo "  -> pushed to the camera roll"
  fi
done
