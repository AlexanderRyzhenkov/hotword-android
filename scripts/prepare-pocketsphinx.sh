#!/usr/bin/env bash
# Prepare all PocketSphinx build inputs on the build machine.
# Nothing is downloaded by the Android app itself.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
aar="$root/app/libs/pocketsphinx-android-5prealpha-release.aar"
assets="$root/app/src/main/assets/pocketsphinx/ru"

PS_ANDROID_COMMIT="8f5ccb88eb24eb5155f52a7366911e5c615e254e"
PS_ANDROID_BLOB_SHA1="745ebd6f70ba101a80bd5c90d1094284e803044c"
MODEL_SHA256="$(tr -d '[:space:]' < "$root/models/pocketsphinx-ru.sha256")"
MODEL_URL="https://sourceforge.net/projects/cmusphinx/files/Acoustic%20and%20Language%20Models/Russian/cmusphinx-ru-5.2.tar.gz/download"

mkdir -p "$(dirname "$aar")"

verify_git_blob() {
  local file="$1" expected="$2"
  local actual
  actual="$(git hash-object "$file")"
  [[ "$actual" == "$expected" ]]
}

if [[ ! -s "$aar" ]] || ! verify_git_blob "$aar" "$PS_ANDROID_BLOB_SHA1"; then
  rm -f "$aar"
  echo "Downloading pinned PocketSphinx Android AAR..."
  curl -fL --retry 3 --retry-all-errors --connect-timeout 20 --max-time 300 \
    --show-error --silent \
    -o "$aar.part" \
    "https://raw.githubusercontent.com/cmusphinx/pocketsphinx-android-demo/$PS_ANDROID_COMMIT/aars/pocketsphinx-android-5prealpha-release.aar"
  mv "$aar.part" "$aar"
  if ! verify_git_blob "$aar" "$PS_ANDROID_BLOB_SHA1"; then
    echo "PocketSphinx AAR git-blob checksum mismatch" >&2
    rm -f "$aar"
    exit 1
  fi
fi

acoustic=(feat.params feature_transform mdef means mixture_weights noisedict transition_matrices variances)
assets_ready=true
for name in "${acoustic[@]}"; do
  [[ -s "$assets/$name" ]] || assets_ready=false
done
[[ -s "$assets/ru.lexicon.gz" ]] || assets_ready=false
if [[ "$assets_ready" == true ]] && [[ -f "$assets/.source-sha256" ]] && \
   [[ "$(tr -d '[:space:]' < "$assets/.source-sha256")" == "$MODEL_SHA256" ]]; then
  echo "Pinned Russian PocketSphinx acoustic model and lexicon already prepared."
  exit 0
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
archive="$tmp/model.tar.gz"

echo "Downloading pinned CMUSphinx Russian model package..."
curl -fL --retry 3 --retry-all-errors --retry-delay 3 --connect-timeout 20 --max-time 900 \
  --show-error --silent -o "$archive" "$MODEL_URL"

actual="$(sha256sum "$archive" | awk '{print $1}')"
if [[ "$actual" != "$MODEL_SHA256" ]]; then
  echo "Russian model SHA-256 mismatch: $actual" >&2
  exit 1
fi

mkdir -p "$tmp/unpack"
tar -xzf "$archive" -C "$tmp/unpack"

transform="$(find "$tmp/unpack" -type f -name feature_transform -print -quit)"
dictionary="$(find "$tmp/unpack" -type f -name ru.dic -print -quit)"
if [[ -z "$transform" || -z "$dictionary" ]]; then
  echo "Cannot find Russian acoustic model or ru.dic in archive" >&2
  exit 1
fi
model_dir="$(dirname "$transform")"

# These common wake-phrase words are CI sanity checks for the upstream lexicon.
for word in алиса слушай привет помощник; do
  if ! grep -m1 -E "^${word}(\\([0-9]+\\))?[[:space:]]+" "$dictionary" >/dev/null; then
    echo "Official Russian dictionary is missing expected word: $word" >&2
    exit 1
  fi
done

rm -rf "$assets"
mkdir -p "$assets"
for name in "${acoustic[@]}"; do
  if [[ ! -s "$model_dir/$name" ]]; then
    echo "Acoustic model file missing: $name" >&2
    exit 1
  fi
  cp "$model_dir/$name" "$assets/$name"
done

# Keep the official 545k-word pronunciation lexicon compressed in the APK.
# At runtime Hotword streams it only when a phrase changes, then creates a
# tiny phrase-only PocketSphinx dictionary. It is never loaded wholesale.
gzip -n -9 -c "$dictionary" > "$assets/ru.lexicon.gz"
printf '%s\n' "$MODEL_SHA256" > "$assets/.source-sha256"

echo "PocketSphinx resources prepared:"
du -sh "$aar" "$assets"
