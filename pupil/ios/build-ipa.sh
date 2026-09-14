#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$ROOT_DIR"
PROJECT_FILE="$PROJECT_DIR/Umtomi.xcodeproj"
SCHEME="Umtomi"
CONFIGURATION="${CONFIGURATION:-Release}"
BUILD_DIR="$PROJECT_DIR/build"
DERIVED_DATA_PATH="$BUILD_DIR/DerivedData"
ARCHIVE_PATH="$BUILD_DIR/Umtomi.xcarchive"
EXPORT_PATH="$BUILD_DIR/export"
UNSIGNED_PAYLOAD_PATH="$BUILD_DIR/Payload"
EXPORT_OPTIONS_PLIST="$BUILD_DIR/ExportOptions.plist"
ICLOUD_IPA_DIR="${HOME}/Library/Mobile Documents/com~apple~CloudDocs/IPA"

require_command() {
    if ! command -v "$1" >/dev/null 2>&1; then
        echo "error: required command '$1' is not installed." >&2
        exit 1
    fi
}

prefer_full_xcode() {
    if [[ -z "${DEVELOPER_DIR:-}" ]] && [[ -d /Applications/Xcode.app/Contents/Developer ]]; then
        export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
    fi
}

require_full_xcode() {
    if ! xcrun --sdk iphoneos --show-sdk-path >/dev/null 2>&1; then
        cat >&2 <<'EOF'
error: iphoneos SDK is unavailable.
Install full Xcode, open it once to finish setup, then point xcode-select at it:
  sudo xcode-select -s /Applications/Xcode.app/Contents/Developer
EOF
        exit 1
    fi
}

generate_project() {
    xcodegen generate --spec "$PROJECT_DIR/project.yml" >/dev/null
}

build_unsigned_ipa() {
    rm -rf "$DERIVED_DATA_PATH" "$UNSIGNED_PAYLOAD_PATH"

    xcodebuild \
        -project "$PROJECT_FILE" \
        -scheme "$SCHEME" \
        -configuration "$CONFIGURATION" \
        -sdk iphoneos \
        -destination generic/platform=iOS \
        -derivedDataPath "$DERIVED_DATA_PATH" \
        clean build \
        CODE_SIGNING_ALLOWED=NO \
        CODE_SIGNING_REQUIRED=NO \
        CODE_SIGN_IDENTITY="" >/dev/null

    local app_path
    app_path="$(find "$DERIVED_DATA_PATH/Build/Products" -maxdepth 2 -type d -name '*.app' | head -n 1)"

    if [[ -z "$app_path" ]]; then
        echo "error: built app bundle was not found." >&2
        exit 1
    fi

    local executable_name
    executable_name="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleExecutable' "$app_path/Info.plist" 2>/dev/null || true)"

    if [[ -z "$executable_name" || ! -f "$app_path/$executable_name" ]]; then
        echo "error: built app bundle is incomplete; executable was not produced." >&2
        exit 1
    fi

    mkdir -p "$UNSIGNED_PAYLOAD_PATH"
    cp -R "$app_path" "$UNSIGNED_PAYLOAD_PATH/"

    (
        cd "$BUILD_DIR"
        rm -f Umtomi-unsigned.ipa
        /usr/bin/zip -qry Umtomi-unsigned.ipa Payload
    )

    echo "$BUILD_DIR/Umtomi-unsigned.ipa"
}

build_signed_ipa() {
    local team_id="$1"
    local export_method="${EXPORT_METHOD:-development}"

    rm -rf "$ARCHIVE_PATH" "$EXPORT_PATH"
    mkdir -p "$BUILD_DIR"

    /usr/bin/plutil -create xml1 "$EXPORT_OPTIONS_PLIST"
    /usr/bin/plutil -replace method -string "$export_method" "$EXPORT_OPTIONS_PLIST"
    /usr/bin/plutil -replace signingStyle -string automatic "$EXPORT_OPTIONS_PLIST"
    /usr/bin/plutil -replace teamID -string "$team_id" "$EXPORT_OPTIONS_PLIST"

    xcodebuild \
        -project "$PROJECT_FILE" \
        -scheme "$SCHEME" \
        -configuration "$CONFIGURATION" \
        -destination generic/platform=iOS \
        -archivePath "$ARCHIVE_PATH" \
        clean archive \
        DEVELOPMENT_TEAM="$team_id" \
        CODE_SIGN_STYLE=Automatic >/dev/null

    xcodebuild \
        -exportArchive \
        -archivePath "$ARCHIVE_PATH" \
        -exportPath "$EXPORT_PATH" \
        -exportOptionsPlist "$EXPORT_OPTIONS_PLIST" >/dev/null

    local ipa_path
    ipa_path="$(find "$EXPORT_PATH" -maxdepth 1 -type f -name '*.ipa' | head -n 1)"

    if [[ -z "$ipa_path" ]]; then
        echo "error: exported ipa was not found." >&2
        exit 1
    fi

    echo "$ipa_path"
}

copy_to_icloud_if_available() {
    local ipa_path="$1"
    local timestamp
    local base_name
    local extension
    local destination_name

    timestamp="$(date '+%y%m%d-%H%M')"
    base_name="$(basename "$ipa_path")"
    extension="${base_name##*.}"
    base_name="${base_name%.*}"
    destination_name="${base_name}-${timestamp}.${extension}"

    mkdir -p "$ICLOUD_IPA_DIR"
    find "$ICLOUD_IPA_DIR" -maxdepth 1 -type f -iname 'Umtomi-*.ipa' -delete
    cp -f "$ipa_path" "$ICLOUD_IPA_DIR/$destination_name"
}

main() {
    require_command xcodegen
    require_command xcodebuild
    prefer_full_xcode
    require_full_xcode
    mkdir -p "$BUILD_DIR"
    generate_project

    local ipa_path

    if [[ -n "${APPLE_TEAM_ID:-}" ]]; then
        ipa_path="$(build_signed_ipa "$APPLE_TEAM_ID")"
    else
        ipa_path="$(build_unsigned_ipa)"
    fi

    copy_to_icloud_if_available "$ipa_path"
    echo "$ipa_path"
}

main "$@"
