#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if (($# == 0)); then set -- :app:assembleDebug; fi
# Cloud-local tool activation and HTTPS proxy trust. No credentials are embedded.
if [[ -f /workspace/tools/java-network.py ]]; then
  exec python3 /workspace/tools/java-network.py /workspace/tools/gradle-8.9/bin/gradle --no-daemon --max-workers=2 "$@"
fi
exec ./gradlew --no-daemon --max-workers=2 "$@"
