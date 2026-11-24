#!/bin/sh
# Makes the app icons:
#   AppIcon.icns (macOS before macOS 26)
#   AppIcon.ico (Windows)
#   AppIcon.png (Linux, and the window icon on Windows and Linux)
# The .icon (macOS 26 and later) is made by hand in Icon Composer
# Needs: sips, iconutil (built into macOS), rsvg-convert and magick (brew install librsvg imagemagick).
set -eu
cd "$(dirname "$0")"

SVG=../../assets/Rounded/mauveclientround.svg
PNG=../../assets/Rounded/mauveclientround.png

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

width="$(sips -g pixelWidth "$PNG" | awk '/pixelWidth/ { print $2 }')"
height="$(sips -g pixelHeight "$PNG" | awk '/pixelHeight/ { print $2 }')"
if [ "$width" != "$height" ] || [ "$width" -lt 1024 ]; then
    echo "$PNG must be square and at least 1024x1024 (it is ${width}x${height})" >&2
    exit 1
fi

# macOS < 26
mkdir "$tmp/AppIcon.iconset"
for size in 16 32 128 256 512; do
    sips -z "$size" "$size" "$PNG" --out "$tmp/AppIcon.iconset/icon_${size}x${size}.png" >/dev/null
    double=$((size * 2))
    sips -z "$double" "$double" "$PNG" --out "$tmp/AppIcon.iconset/icon_${size}x${size}@2x.png" >/dev/null
done
iconutil -c icns "$tmp/AppIcon.iconset" -o AppIcon.icns

# Windows
for size in 16 24 32 48 64 128 256; do
    rsvg-convert -w "$size" -h "$size" "$SVG" -o "$tmp/ico-$size.png"
done
magick "$tmp"/ico-16.png "$tmp"/ico-24.png "$tmp"/ico-32.png "$tmp"/ico-48.png \
    "$tmp"/ico-64.png "$tmp"/ico-128.png "$tmp"/ico-256.png AppIcon.ico

# Linux
rsvg-convert -w 512 -h 512 "$SVG" -o AppIcon.png

echo "Wrote AppIcon.icns, AppIcon.ico and AppIcon.png"