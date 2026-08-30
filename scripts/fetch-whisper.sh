#!/usr/bin/env bash
# Restores the two large, gitignored build inputs: the vendored whisper.cpp
# source and the Tiny model that ships inside the APK. Both are required —
# without the model the app builds but has nothing to transcribe with.
set -euo pipefail
cd "$(dirname "$0")/.."

if [ -d third_party/whisper.cpp ]; then
  echo "third_party/whisper.cpp already present"
else
  git clone --depth 1 --branch v1.7.6 https://github.com/ggml-org/whisper.cpp third_party/whisper.cpp
fi

MODEL=app/src/main/assets/models/ggml-tiny-q5_1.bin
if [ -f "$MODEL" ]; then
  echo "$MODEL already present"
else
  mkdir -p "$(dirname "$MODEL")"
  curl -sSL --fail -o "$MODEL" \
    https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin
  echo "fetched $MODEL"
fi
