"""Create offline Android license notices from the resolved runtime artifact inventory."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import urllib.request
import xml.etree.ElementTree as ET
import zipfile


def embedded_notices(path):
    notices = []
    def inspect(archive, depth=0):
        for member in archive.infolist():
            leaf = member.filename.rsplit('/', 1)[-1].lower()
            if member.file_size > 262144:
                continue
            if re.match(r'^(license|notice|copyright)([._-].*)?$', leaf):
                value = archive.read(member).decode('utf-8', errors='replace').strip()
                if value:
                    notices.append((member.filename, value))
            elif depth == 0 and leaf == 'classes.jar':
                try:
                    with zipfile.ZipFile(io.BytesIO(archive.read(member))) as nested:
                        inspect(nested, 1)
                except zipfile.BadZipFile:
                    pass
    with zipfile.ZipFile(path) as archive:
        inspect(archive)
    return notices


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--inventory', type=Path, required=True)
    parser.add_argument('--cache', type=Path, required=True)
    parser.add_argument('--project', type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    assets = args.project / 'android/app/src/main/assets/licenses'
    assets.mkdir(parents=True, exist_ok=True)
    license_path = assets / 'apache-2.0.txt'
    if not license_path.exists():
        with urllib.request.urlopen('https://www.apache.org/licenses/LICENSE-2.0.txt', timeout=30) as response:
            license_path.write_bytes(response.read())
    apache = license_path.read_text(encoding='utf-8')
    assert 'Apache License' in apache and 'Version 2.0' in apache
    rows = json.loads(args.inventory.read_text(encoding='utf-8-sig'))
    components = []
    seen = set()
    namespace = {'m': 'http://maven.apache.org/POM/4.0.0'}
    for row in rows:
        coordinate = f"{row['group']}:{row['name']}"
        key = (coordinate, row['version'])
        if key in seen:
            continue
        seen.add(key)
        pom_folder = args.cache / row['group'] / row['name'] / row['version']
        candidates = list(pom_folder.glob('**/*.pom'))
        if not candidates:
            raise RuntimeError(f'Missing POM for {key}')
        root = ET.parse(candidates[0]).getroot()
        metadata = root
        for _ in range(8):
            if metadata.findall('m:licenses/m:license/m:name', namespace):
                break
            parent = metadata.find('m:parent', namespace)
            if parent is None:
                break
            parent_coordinate = [parent.findtext('m:'+field, default='', namespaces=namespace)
                                 for field in ('groupId', 'artifactId', 'version')]
            parent_files = list(args.cache.joinpath(*parent_coordinate).glob('**/*.pom'))
            if not parent_files:
                break
            metadata = ET.parse(parent_files[0]).getroot()
        licenses = metadata.findall('m:licenses/m:license/m:name', namespace)
        names = [item.text or '' for item in licenses]
        if not names or not all('apache' in name.lower() for name in names):
            raise RuntimeError(f'License requires review: {key}: {names}')
        url = root.findtext('m:url', namespaces=namespace) or metadata.findtext('m:url', default='', namespaces=namespace)
        author = metadata.findtext('m:organization/m:name', default='', namespaces=namespace)
        developers = [item.text for item in metadata.findall('m:developers/m:developer/m:name', namespace) if item.text]
        if not author:
            author = ', '.join(developers) or {
                'androidx': 'The Android Open Source Project',
                'org.jetbrains': 'JetBrains and contributors',
                'com.squareup': 'Square, Inc. and contributors',
            }.get(next((prefix for prefix in ('androidx', 'org.jetbrains', 'com.squareup') if row['group'].startswith(prefix)), ''), row['group'])
        notes = embedded_notices(row['file'])
        text_asset = 'licenses/apache-2.0.txt'
        if notes:
            digest = hashlib.sha256((coordinate+row['version']).encode()).hexdigest()[:16]
            name = f'notice-{digest}.txt'
            text = f"{coordinate} {row['version']}\n{author}\n{url}\n\n{apache}\n\n"
            text += '\n\n'.join(f'--- {filename} ---\n{value}' for filename, value in notes)
            (assets / name).write_text(text, encoding='utf-8')
            text_asset = 'licenses/' + name
        components.append(dict(name=coordinate, version=row['version'], author=author,
                               url=url, license='Apache-2.0', textAsset=text_asset))
    backend_notices = args.project / 'backend/THIRD_PARTY_NOTICES.txt'
    if backend_notices.exists():
        (assets/'backend-notices.txt').write_bytes(backend_notices.read_bytes())
        components.append(dict(name='整理服务后端及验证工具依赖', version='见许可清单',
                               author='各项目作者，详见原始版权声明', url='',
                               license='MIT / BSD / Apache / MPL 等，以各项原文为准',
                               textAsset='licenses/backend-notices.txt'))
    index = dict(components=components)
    (assets/'index.json').write_text(json.dumps(index, ensure_ascii=False, indent=2), encoding='utf-8')
    for component in components:
        assert (assets.parent/component['textAsset']).is_file()
    print(json.dumps({'runtime_components':len(seen), 'offline_notice_entries':len(components)}))


if __name__ == '__main__':
    main()
