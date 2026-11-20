"""Publish a verified APK to the user-approved public APK-only repository.
Uses the existing Git credential manager; no credential is written to disk.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.error
import urllib.request

REPO = 'omyeol/Umtomi'
API = 'https://api.github.com/'

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('apk', type=Path)
    parser.add_argument('--notes', required=True, type=Path)
    parser.add_argument('--publish', action='store_true', help='Publish after uploading; otherwise keep draft')
    args = parser.parse_args()
    match = re.fullmatch(r'Umtomi-v(\d+\.\d+\.\d+)(?:-debug)?\.apk', args.apk.name)
    if not match or not args.apk.is_file():
        raise SystemExit('Expected an existing versioned Umtomi APK')
    tag = 'v' + match.group(1)
    payload = args.apk.read_bytes()
    checksum = hashlib.sha256(payload).hexdigest()
    env = dict(os.environ, GIT_TERMINAL_PROMPT='0', GCM_INTERACTIVE='never')
    result = subprocess.run(['git', 'credential', 'fill'], input='protocol=https\nhost=github.com\n\n', text=True, capture_output=True, env=env, timeout=30)
    credentials = dict(line.split('=', 1) for line in result.stdout.splitlines() if '=' in line)
    token = credentials.get('password')
    if not token:
        raise SystemExit('GitHub authentication unavailable')
    def request(path, method='GET', data=None, binary=False):
        url = path if path.startswith('https://uploads.github.com/') else API + path
        headers = {'Authorization': 'Bearer ' + token, 'Accept': 'application/vnd.github+json', 'User-Agent': 'Umtomi-release', 'X-GitHub-Api-Version': '2022-11-28'}
        if data is not None:
            headers['Content-Type'] = 'application/octet-stream' if binary else 'application/json'
            if not binary: data = json.dumps(data).encode()
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        with urllib.request.urlopen(req, timeout=120) as response:
            body = response.read()
            return json.loads(body) if body else None
    repo = request('repos/' + REPO)
    if repo['private'] or repo['full_name'] != REPO:
        raise SystemExit('Unexpected release repository identity or visibility')
    releases = request('repos/' + REPO + '/releases?per_page=100')
    release = next((r for r in releases if r['tag_name'] == tag), None)
    notes = args.notes.read_text(encoding='utf-8') + '\n\nSHA-256 (`' + args.apk.name + '`):\n`' + checksum + '`\n'
    if release is None:
        release = request('repos/' + REPO + '/releases', 'POST', {'tag_name':tag, 'name':'Umtomi ' + match.group(1), 'body':notes, 'draft':True, 'prerelease':False, 'target_commitish':repo['default_branch']})
    if not release['draft']:
        raise SystemExit('Release already published; do not overwrite a published APK')
    request('repos/' + REPO + '/releases/' + str(release['id']), 'PATCH', {'body':notes, 'tag_name':tag})
    assets = request('repos/' + REPO + '/releases/' + str(release['id']) + '/assets')
    for name, content in [(args.apk.name, payload), ('SHA256SUMS.txt', (checksum + '  ' + args.apk.name + '\n').encode())]:
        existing = next((a for a in assets if a['name'] == name), None)
        if existing:
            if existing.get('digest') == 'sha256:' + hashlib.sha256(content).hexdigest():
                continue
            raise SystemExit('Different existing draft asset: ' + name)
        asset = request(release['upload_url'].split('{')[0] + '?name=' + name, 'POST', content, binary=True)
        if asset['size'] != len(content) or asset.get('digest') not in (None, 'sha256:' + hashlib.sha256(content).hexdigest()):
            raise SystemExit('Upload verification failed: ' + name)
    if args.publish:
        release = request('repos/' + REPO + '/releases/' + str(release['id']), 'PATCH', {'tag_name':tag, 'draft':False, 'make_latest':'true'})
    print(json.dumps({'url':release['html_url'], 'draft':release['draft'], 'sha256':checksum, 'source_private':repo['private']}))

if __name__ == '__main__':
    main()
