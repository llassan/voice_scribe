#!/usr/bin/env bash
# Restores the vendored whisper.cpp source (gitignored to keep the repo small).
set -euo pipefail
cd "$(dirname "$0")/.."
if [ -d third_party/whisper.cpp ]; then
  echo "third_party/whisper.cpp already present"
  exit 0
fi
git clone --depth 1 --branch v1.7.6 https://github.com/ggml-org/whisper.cpp third_party/whisper.cpp
