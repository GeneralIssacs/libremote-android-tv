#!/usr/bin/env bash
# Renders the store graphics of both apps from the SVGs in art/.
# Needs rsvg-convert, oxipng and the Inter font. Usage: tools/render-art.sh   (run from the repository root)
set -euo pipefail
app=app/fastlane/metadata/android/en-US/images
tv=tv/fastlane/metadata/android/en-US/images
mkdir -p "$app" "$tv"
render() { rsvg-convert -w "$2" -h "$3" "art/$1" -o "$4" && oxipng -q -o 4 --strip all "$4" && echo "$4"; }
render icon.svg 512 512 "$app/icon.png"
render feature-graphic.svg 1024 500 "$app/featureGraphic.png"
render receiver-icon.svg 512 512 "$tv/icon.png"
render receiver-banner.svg 1280 720 "$tv/tvBanner.png"
