#!/bin/sh
set -eu

GRADLE_VERSION="9.4.1"
CACHE_DIR="${GRADLE_USER_HOME:-$HOME/.gradle}/fatline-bootstrap"
GRADLE_HOME="$CACHE_DIR/gradle-$GRADLE_VERSION"
ZIP="$CACHE_DIR/gradle-$GRADLE_VERSION-bin.zip"
URL="https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"

if [ -x "$GRADLE_HOME/bin/gradle" ]; then
  exec "$GRADLE_HOME/bin/gradle" "$@"
fi

if command -v gradle >/dev/null 2>&1; then
  exec gradle "$@"
fi

mkdir -p "$CACHE_DIR"
if [ ! -f "$ZIP" ]; then
  echo "Gradle $GRADLE_VERSION is not installed; downloading the official distribution..." >&2
  if command -v curl >/dev/null 2>&1; then
    curl --fail --location --retry 3 --output "$ZIP" "$URL"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$ZIP" "$URL"
  else
    echo "Need curl, wget, or Gradle $GRADLE_VERSION on PATH." >&2
    exit 1
  fi
fi

if ! command -v unzip >/dev/null 2>&1; then
  echo "Need unzip to bootstrap Gradle." >&2
  exit 1
fi

rm -rf "$GRADLE_HOME"
unzip -q "$ZIP" -d "$CACHE_DIR"
exec "$GRADLE_HOME/bin/gradle" "$@"
