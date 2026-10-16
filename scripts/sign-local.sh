#!/usr/bin/env bash
# Local cloud signing material is deliberately kept outside the source checkout.
set -euo pipefail
cd "$(dirname "$0")/.."
build_tools="${ANDROID_HOME:-/workspace/tools/android-sdk}/build-tools/35.0.0"
signing_dir="${LANFTP_SIGNING_DIR:-/workspace/tools/signing}"
version="${1:-2.0}"
[[ "$version" =~ ^[0-9]+(\.[0-9]+){1,2}$ ]] || { echo "Invalid version" >&2; exit 1; }
mkdir -p dist
"$build_tools/zipalign" -f -p 4 app/build/outputs/apk/release/app-release-unsigned.apk dist/LanFTP-aligned.apk
"$build_tools/apksigner" sign --ks "$signing_dir/lanftp.p12" --ks-key-alias lanftp --ks-pass "file:$signing_dir/password" --out dist/LanFTP-${version}.apk dist/LanFTP-aligned.apk
"$build_tools/apksigner" verify --verbose dist/LanFTP-${version}.apk
"$build_tools/zipalign" -c -p 4 dist/LanFTP-${version}.apk
rm dist/LanFTP-aligned.apk
sha256sum dist/LanFTP-${version}.apk > dist/SHA256SUMS
