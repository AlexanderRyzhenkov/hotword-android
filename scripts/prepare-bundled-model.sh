#!/usr/bin/env bash
# Download the pinned model ON THE BUILD MACHINE, never on a user's phone.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
file="$root/app/src/main/assets/models/ru.zip"
expected="$(tr -d '[:space:]' < "$root/models/ru.sha256")"
sha256() { sha256sum "$1" | awk '{print $1}'; }
if [[ -s "$file" ]] && [[ "$(sha256 "$file")" == "$expected" ]]; then
  echo "Bundled Russian Vosk model already verified."
  exit 0
fi
mkdir -p "$(dirname "$file")"
rm -f "$file"
partial="$file.part"
trap 'rm -f "$partial"' EXIT
sources=(
 "https://github.com/BartekReterski/VoskModels/releases/download/v1/vosk-model-small-ru-0.22.zip"
 "https://huggingface.co/rhasspy/vosk-models/resolve/main/ru/vosk-model-small-ru-0.22.zip"
 "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"
)
for url in "${sources[@]}"; do
  rm -f "$partial"
  echo "Trying $url"
  if curl -fL --retry 2 --retry-all-errors --retry-delay 3 --connect-timeout 20 --max-time 300 --show-error --silent -o "$partial" "$url"; then
    actual="$(sha256 "$partial")"
    if [[ "$actual" == "$expected" ]]; then
      mv "$partial" "$file"
      echo "Bundled model checksum verified."
      exit 0
    fi
    echo "SHA-256 mismatch for $url: $actual" >&2
  else
    echo "Download failed; trying next source." >&2
  fi
done
echo "Failed to download the pinned Russian Vosk model on the build machine." >&2
echo "Alternatively place vosk-model-small-ru-0.22.zip in app/src/main/assets/models/ru.zip." >&2
echo "The expected SHA-256 is in models/ru.sha256." >&2
exit 1
