import argparse
import base64
import hashlib
import io
import json
import os
from pathlib import Path
import plistlib
import re
import subprocess
import urllib.error
import urllib.parse
import urllib.request
import zipfile

REPO = "omyeol/Umtomi"
LEGACY_FEED_REPO = "omyeol/Umtomi-releases"
BUNDLE = "xyz.sabok.umtomi"

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("ipa", type=Path)
    parser.add_argument("--date", required=True)
    parser.add_argument("--notes", required=True, type=Path)
    args = parser.parse_args()
    ipa = args.ipa.read_bytes()
    with zipfile.ZipFile(io.BytesIO(ipa)) as z:
        if z.testzip() is not None:
            raise SystemExit("Corrupt IPA")
        info = plistlib.loads(z.read("Payload/Umtomi.app/Info.plist"))
    version = info["CFBundleShortVersionString"]
    build = info["CFBundleVersion"]
    if not re.fullmatch(r"\d+\.\d+\.\d+", version) or not re.fullmatch(r"\d+", build):
        raise SystemExit("Unexpected iOS version")
    if info["CFBundleIdentifier"] != BUNDLE or args.ipa.name != f"Umtomi-iOS-{version}.ipa":
        raise SystemExit("Unexpected iOS bundle or filename")
    tag = f"ios-v{version}"
    checksum = hashlib.sha256(ipa).hexdigest()
    notes = args.notes.read_text(encoding="utf-8-sig")
    env = dict(os.environ, GIT_TERMINAL_PROMPT="0", GCM_INTERACTIVE="never")
    result = subprocess.run(["git", "credential", "fill"], input="protocol=https\nhost=github.com\n\n",
                            text=True, capture_output=True, env=env, timeout=20, check=True)
    token = dict(line.split("=", 1) for line in result.stdout.splitlines() if "=" in line).get("password")
    if not token:
        raise SystemExit("GitHub credential unavailable")

    def request(path, method="GET", data=None, binary=False, allow_missing=False, public=False):
        if path.startswith("https://uploads.github.com/"):
            url = path
        else:
            url = "https://api.github.com" + path
        headers = {"Accept": "application/vnd.github+json", "User-Agent": "Umtomi-iOS-release"}
        if not public:
            headers["Authorization"] = "Bearer " + token
        if data is not None:
            headers["Content-Type"] = "application/octet-stream" if binary else "application/json"
            if not binary:
                data = json.dumps(data).encode()
        try:
            with urllib.request.urlopen(urllib.request.Request(url, data=data, headers=headers, method=method), timeout=60) as response:
                body = response.read()
                return json.loads(body) if body else None
        except urllib.error.HTTPError as e:
            if allow_missing and e.code == 404:
                return None
            raise SystemExit(f"GitHub {method} failed (HTTP {e.code})")

    target_path = "/repos/" + REPO
    repo = request(target_path)
    if repo["private"] or repo["full_name"] != REPO:
        raise SystemExit("Unexpected repository visibility")
    source_path = target_path + "/contents/livecontainer.json"
    source = request(source_path, allow_missing=True)
    legacy_source_path = f"/repos/{LEGACY_FEED_REPO}/contents/livecontainer.json"
    legacy_source = request(legacy_source_path, allow_missing=True)
    seed_source = source or legacy_source
    if seed_source is None:
        raise SystemExit("LiveContainer source missing")
    source_bytes = base64.b64decode(seed_source["content"])
    feed = json.loads(source_bytes)
    feed["website"] = f"https://github.com/{REPO}"
    app = next((app for app in feed["apps"] if app.get("bundleIdentifier") == BUNDLE), None)
    if app is None:
        raise SystemExit("App not found in existing LiveContainer source")
    url = f"https://github.com/{REPO}/releases/download/{tag}/{args.ipa.name}"
    entry = {"version": version, "buildVersion": build, "buildNumber": build, "date": args.date,
             "downloadURL": url, "size": len(ipa), "minOSVersion": info["MinimumOSVersion"]}
    previous = app.get("versions", [])
    match = [v for v in previous if (v["version"], v.get("buildVersion")) == (version, build)]
    if match and any(v != entry for v in match):
        raise SystemExit("Existing LiveContainer version entry disagrees with the IPA")
    if not match:
        app["versions"] = [entry] + previous
    updated = (json.dumps(feed, ensure_ascii=False, indent=2) + "\n").encode()

    releases = target_path + "/releases"
    release = request(releases + "/tags/" + tag, allow_missing=True)
    if release is None:
        # Draft releases do not always appear on the tag endpoint until published.
        release = next((r for r in request(releases + "?per_page=100") if r["tag_name"] == tag), None)
    if release is None:
        release = request(releases, "POST", {"tag_name": tag, "name": "Umtomi iOS " + version, "draft": True,
                                             "body": notes, "prerelease": False, "make_latest": "false",
                                             "target_commitish": repo["default_branch"]})
    release_path = releases + "/" + str(release["id"])
    assets = request(release_path + "/assets")
    for name, content in ((args.ipa.name, ipa), ("SHA256SUMS.txt", f"{checksum}  {args.ipa.name}\n".encode()),
                          ("livecontainer.json", updated)):
        expected = "sha256:" + hashlib.sha256(content).hexdigest()
        found = next((asset for asset in assets if asset["name"] == name), None)
        if found is None:
            target = release["upload_url"].split("{")[0] + "?name=" + urllib.parse.quote(name)
            found = request(target, "POST", content, binary=True)
        if found.get("digest") != expected or found["size"] != len(content):
            raise SystemExit(f"Release asset verification failed: {name}")
    if release["draft"]:
        release = request(release_path, "PATCH", {"draft": False, "make_latest": "false"})
    public = request(releases + "/tags/" + tag, public=True)
    if public["draft"] or not any(a["browser_download_url"] == url and a.get("digest") == "sha256:" + checksum for a in public["assets"]):
        raise SystemExit("Public release verification failed")

    if source is None or updated != base64.b64decode(source["content"]):
        payload = {"message": "Update LiveContainer source to iOS " + version,
                   "content": base64.b64encode(updated).decode(),
                   "branch": repo["default_branch"]}
        if source is not None:
            payload["sha"] = source["sha"]
        request(source_path, "PUT", payload)
    if legacy_source is not None and updated != base64.b64decode(legacy_source["content"]):
        legacy_repo = request(f"/repos/{LEGACY_FEED_REPO}")
        request(legacy_source_path, "PUT", {"message": "Update LiveContainer source to iOS " + version,
                                            "content": base64.b64encode(updated).decode(), "sha": legacy_source["sha"],
                                            "branch": legacy_repo["default_branch"]})
    public_source = request(source_path, public=True)
    reread = json.loads(base64.b64decode(public_source["content"]))
    current = next(a for a in reread["apps"] if a["bundleIdentifier"] == BUNDLE)
    if current["versions"][0] != entry:
        raise SystemExit("LiveContainer source verification failed")
    latest = request(releases + "/latest", public=True, allow_missing=True)
    print(json.dumps({"release": release["html_url"], "ipa": url, "sha256": checksum,
                      "feed": public_source["download_url"], "android_latest": latest["tag_name"] if latest else None}))

if __name__ == "__main__":
    main()
