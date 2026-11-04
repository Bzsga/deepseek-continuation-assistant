"""Publish an explicitly verified APK to the isolated static update channel."""

import argparse
import hashlib
import importlib.util
import json
import re
import sys
import uuid
import zipfile
from datetime import datetime, timezone
from pathlib import Path


PACKAGE_NAME = "app.banzhan.deepseekcontinuation"
ORIGIN = "https://digimonlegend.com"
PREFIX = "/continuation-updates"
REMOTE_ROOT = "/var/www/deepseek-continuation-updates"
MAX_APK_BYTES = 64 * 1024 * 1024
MAX_MANIFEST_BYTES = 32 * 1024
NUMERIC_VERSION = r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)"

NGINX_SNIPPET = '''# Managed by Deepseek continuation publish_release.py.
location = /continuation-updates/latest.json {
    alias /var/www/deepseek-continuation-updates/latest.json;
    default_type application/json;
    add_header Cache-Control "no-store" always;
    add_header X-Content-Type-Options "nosniff" always;
}
location ^~ /continuation-updates/apks/ {
    if ($uri !~ "\\.apk$") { return 404; }
    alias /var/www/deepseek-continuation-updates/apks/;
    types { application/vnd.android.package-archive apk; }
    default_type application/vnd.android.package-archive;
    autoindex off;
    add_header Cache-Control "public, max-age=31536000, immutable";
    add_header X-Content-Type-Options "nosniff" always;
    limit_except GET { deny all; }
}
location ^~ /continuation-updates/ { return 404; }
'''


class PublishError(Exception):
    def __init__(self, code):
        super().__init__(code)
        self.code = code


def file_sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_json(path, maximum=MAX_MANIFEST_BYTES):
    with Path(path).open("rb") as stream:
        raw = stream.read(maximum + 1)
    if len(raw) > maximum:
        raise PublishError("metadata_too_large")
    try:
        return json.loads(raw.decode("utf-8-sig"))
    except (ValueError, UnicodeError, RecursionError):
        raise PublishError("metadata_invalid_json") from None


def prepare_release(apk_path, verified_metadata_path, changelog=None, released_at=None):
    apk = Path(apk_path).resolve(strict=True)
    size = apk.stat().st_size
    if not apk.is_file() or apk.suffix.lower() != ".apk" or not 0 < size <= MAX_APK_BYTES:
        raise PublishError("apk_size_or_path_invalid")
    metadata = read_json(verified_metadata_path)
    if not isinstance(metadata, dict) or metadata.get("signatureVerified") is not True:
        raise PublishError("signature_verification_assertion_required")
    if metadata.get("packageName") != PACKAGE_NAME:
        raise PublishError("package_mismatch")
    version = metadata.get("versionCode")
    name = metadata.get("versionName")
    minimum = metadata.get("minSdk")
    if type(version) is not int or not 1 <= version <= 2_147_483_647:
        raise PublishError("version_code_invalid")
    if not isinstance(name, str) or not name.strip() or len(name) > 80:
        raise PublishError("version_name_invalid")
    if version >= 10 and not re.fullmatch(NUMERIC_VERSION, name):
        raise PublishError("numeric_version_name_required")
    if type(minimum) is not int or not 1 <= minimum <= 100:
        raise PublishError("min_sdk_invalid")
    signer = metadata.get("signingCertificateSha256")
    asserted_hash = metadata.get("apkSha256")
    if not isinstance(signer, str) or not re.fullmatch(r"[0-9A-Fa-f]{64}", signer):
        raise PublishError("signer_fingerprint_required")
    digest = file_sha256(apk)
    if not isinstance(asserted_hash, str) or asserted_hash.lower() != digest:
        raise PublishError("verified_apk_hash_mismatch")
    try:
        with zipfile.ZipFile(apk) as archive:
            names = archive.namelist()
            if names.count("AndroidManifest.xml") != 1 or names.count("classes.dex") != 1:
                raise PublishError("apk_structure_invalid")
    except zipfile.BadZipFile:
        raise PublishError("apk_structure_invalid") from None
    notes = [] if changelog is None else changelog
    if (not isinstance(notes, list) or len(notes) > 8
            or any(not isinstance(note, str) or not note.strip() or len(note) > 200 for note in notes)):
        raise PublishError("changelog_invalid")
    timestamp = released_at or datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")
    try:
        parsed_time = datetime.fromisoformat(timestamp.replace("Z", "+00:00"))
        if parsed_time.tzinfo is None:
            raise ValueError
    except (ValueError, TypeError, AttributeError):
        raise PublishError("release_time_invalid") from None
    manifest = {
        "schemaVersion": 1, "packageName": PACKAGE_NAME, "versionCode": version, "versionName": name,
        "apkUrl": f"{ORIGIN}{PREFIX}/apks/{version}/deepseek-continuation-{version}.apk",
        "apkSize": size, "sha256": digest, "minSdk": minimum, "releasedAt": timestamp,
        "changelog": notes,
    }
    if len(json.dumps(manifest, ensure_ascii=False).encode("utf-8")) > MAX_MANIFEST_BYTES:
        raise PublishError("manifest_too_large")
    return manifest, signer.lower()


def connect(helper_path):
    if not helper_path:
        raise PublishError("explicit_trusted_ssh_helper_required")
    path = Path(helper_path).resolve(strict=True)
    if not path.is_file() or path.suffix.lower() != ".py":
        raise PublishError("ssh_helper_invalid")
    spec = importlib.util.spec_from_file_location("continuation_release_ssh_" + uuid.uuid4().hex, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    if not callable(getattr(module, "connect", None)):
        raise PublishError("ssh_helper_connect_missing")
    return module.connect()


def remote_python(client, code, payload=None):
    source = "import json\nPAYLOAD = json.loads(" + repr(json.dumps(payload or {}, ensure_ascii=False)) + ")\n" + code
    stdin, stdout, stderr = client.exec_command("python3 -", timeout=120)
    stdin.write(source)
    stdin.channel.shutdown_write()
    raw = stdout.read(65537)
    exit_code = stdout.channel.recv_exit_status()
    stderr.read()
    if len(raw) > 65536:
        raise PublishError("remote_response_too_large")
    try:
        result = json.loads(raw.decode("utf-8"))
    except (ValueError, UnicodeError):
        raise PublishError("remote_response_invalid") from None
    if exit_code != 0 or not isinstance(result, dict) or not result.get("ok"):
        code = result.get("error", "remote_operation_failed") if isinstance(result, dict) else "remote_operation_failed"
        if not isinstance(code, str) or not re.fullmatch(r"[a-z_]{1,80}", code):
            code = "remote_operation_failed"
        raise PublishError(code)
    return result


PREPARE_HOSTING = r'''
import datetime, os, shutil, subprocess, sys
from pathlib import Path
root = Path('/var/www/deepseek-continuation-updates')
site = Path('/etc/nginx/sites-enabled/digimon_website').resolve(strict=True)
snippet = Path('/etc/nginx/snippets/deepseek-continuation-updates.conf')
include = '    include /etc/nginx/snippets/deepseek-continuation-updates.conf;'
expected = PAYLOAD['snippet']
backup = None
changed_site = False
created_snippet = False
created_root = False
def fail(code):
    raise RuntimeError(code)
try:
    if site != Path('/etc/nginx/sites-available/digimon_website'):
        fail('site_target_changed')
    original = site.read_bytes()
    text = original.decode('utf-8')
    if snippet.exists() and (snippet.is_symlink() or snippet.read_text() != expected):
        fail('updates_snippet_manually_modified')
    existing = [line for line in text.splitlines() if '/continuation-updates' in line and 'location' in line]
    if existing:
        fail('updates_location_already_defined')
    for path in Path('/etc/nginx/snippets').glob('*.conf'):
        if path != snippet and '/continuation-updates' in path.read_text(errors='replace'):
            fail('updates_location_already_defined')
    if include in text:
        if text.count(include) != 1 or not snippet.exists():
            fail('updates_include_inconsistent')
        if any(path.is_symlink() or not path.is_dir() for path in (root,root/'apks',root/'.staging')):
            fail('static_root_inconsistent')
        print(json.dumps({'ok':True,'status':'already_prepared','static_root':str(root),'snippet':str(snippet)}))
        raise SystemExit(0)
    if root.exists() or root.is_symlink():
        fail('static_root_already_exists_unmanaged')
    anchor = '    include /etc/nginx/snippets/deepseek-continuation-location.conf;'
    if text.count(anchor) != 1:
        fail('expected_ssl_include_anchor_missing')
    backup_dir = Path('/etc/nginx/backups')
    backup_dir.mkdir(mode=0o700,exist_ok=True)
    backup = backup_dir / ('digimon_website.pre-continuation-updates-' + datetime.datetime.now().strftime('%Y%m%d-%H%M%S'))
    if backup.exists():
        fail('backup_path_exists')
    shutil.copy2(site,backup)
    backup.chmod(0o600)
    root.mkdir(mode=0o755)
    created_root = True
    (root/'apks').mkdir(mode=0o755)
    (root/'.staging').mkdir(mode=0o700)
    snippet.write_text(expected)
    snippet.chmod(0o644)
    created_snippet = True
    if site.read_bytes() != original:
        fail('site_changed_during_prepare')
    temp = site.with_name(site.name + '.continuation-updates-tmp')
    temp.write_text(text.replace(anchor,anchor+'\n'+include))
    shutil.copystat(site,temp)
    os.replace(temp,site)
    changed_site = True
    checked = subprocess.run(['nginx','-t'],capture_output=True)
    if checked.returncode:
        fail('nginx_validation_failed')
    if subprocess.run(['systemctl','reload','nginx'],capture_output=True).returncode:
        fail('nginx_reload_failed')
    print(json.dumps({'ok':True,'status':'prepared','static_root':str(root),'snippet':str(snippet),'nginx_backup':str(backup)}))
except Exception as error:
    restored = True
    if changed_site and backup:
        shutil.copy2(backup,site)
    if created_snippet and snippet.exists():
        snippet.unlink()
    if changed_site:
        restored = subprocess.run(['nginx','-t'],capture_output=True).returncode == 0
        if restored:
            restored = subprocess.run(['systemctl','reload','nginx'],capture_output=True).returncode == 0
    if created_root:
        for directory in (root/'.staging',root/'apks',root):
            try:directory.rmdir()
            except OSError:pass
    code = str(error) if isinstance(error,RuntimeError) else 'hosting_prepare_failed'
    if not restored:code = 'hosting_restore_failed'
    print(json.dumps({'ok':False,'error':code}))
    raise SystemExit(1)
'''


CREATE_STAGE = r'''
import re,sys
from pathlib import Path
try:
    root=Path('/var/www/deepseek-continuation-updates')
    token=PAYLOAD['stage']
    if not re.fullmatch(r'[0-9a-f]{32}',token):raise RuntimeError('stage_invalid')
    if any(path.is_symlink() or not path.is_dir() for path in (root,root/'apks',root/'.staging')):
        raise RuntimeError('hosting_not_prepared')
    stage=root/'.staging'/token
    stage.mkdir(mode=0o700)
    print(json.dumps({'ok':True,'stage':str(stage)}))
except Exception as error:
    print(json.dumps({'ok':False,'error':str(error) if isinstance(error,RuntimeError) else 'stage_create_failed'}))
    raise SystemExit(1)
'''


COMMIT_RELEASE = r'''
import fcntl,hashlib,os,re,shutil,sys
from pathlib import Path
root=Path('/var/www/deepseek-continuation-updates')
token=PAYLOAD['stage']
stage=root/'.staging'/token
def fail(code):raise RuntimeError(code)
def sha(path):
    result=hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda:stream.read(1048576),b''):result.update(chunk)
    return result.hexdigest()
def read_json(path):
    if path.stat().st_size>32768:fail('remote_metadata_too_large')
    return json.loads(path.read_text())
def numeric_version(name):
    if not isinstance(name,str) or len(name)>80:return None
    matched=re.fullmatch(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)',name)
    return tuple(int(part) for part in matched.groups()) if matched else None
def check_identity(record,manifest,signer):
    if record.get('publisherFormat')!='continuation-release-v1':fail('version_directory_unmanaged')
    saved=record.get('manifest',{})
    for key in ('packageName','versionCode','versionName','apkUrl','apkSize','sha256','minSdk'):
        if saved.get(key)!=manifest[key]:fail('same_version_metadata_or_hash_conflict')
    if record.get('signingCertificateSha256')!=signer:fail('signer_changed')
    return saved
try:
    if any(path.is_symlink() or not path.is_dir() for path in (root,root/'apks',root/'.staging')):
        fail('managed_path_modified')
    if not re.fullmatch(r'[0-9a-f]{32}',token) or stage.is_symlink() or stage.parent.resolve()!=(root/'.staging').resolve():
        fail('stage_invalid')
    manifest=PAYLOAD['manifest'];signer=PAYLOAD['signer']
    version=manifest['versionCode']
    if type(version) is not int or not 1<=version<=2147483647:fail('version_code_invalid')
    name=manifest.get('versionName')
    if not isinstance(name,str) or not name.strip() or len(name)>80:fail('version_name_invalid')
    semantic=numeric_version(name)
    if version>=10 and semantic is None:fail('numeric_version_name_required')
    if manifest['packageName']!='app.banzhan.deepseekcontinuation':fail('package_mismatch')
    expected_url='https://digimonlegend.com/continuation-updates/apks/'+str(version)+'/deepseek-continuation-'+str(version)+'.apk'
    if manifest['apkUrl']!=expected_url:fail('apk_url_invalid')
    package=stage/'package.apk'
    if package.is_symlink() or not package.is_file():fail('staged_apk_missing')
    if package.stat().st_size!=manifest['apkSize'] or not 0<manifest['apkSize']<=67108864:fail('remote_size_mismatch')
    if sha(package)!=manifest['sha256']:fail('remote_sha256_mismatch')
    with package.open('rb+') as stream:os.fsync(stream.fileno())
    with (root/'.publish.lock').open('a+b') as lock:
        fcntl.flock(lock.fileno(),fcntl.LOCK_EX)
        latest_path=root/'latest.json'
        if latest_path.is_symlink():fail('latest_manifest_invalid')
        latest=read_json(latest_path) if latest_path.exists() else None
        if latest is not None:
            if latest.get('packageName')!=manifest['packageName'] or type(latest.get('versionCode')) is not int:
                fail('latest_manifest_invalid')
            if latest['versionCode']>version:fail('version_downgrade_refused')
            previous=root/'apks'/str(latest['versionCode'])/'.release.json'
            if previous.is_symlink() or not previous.is_file():fail('latest_release_unmanaged')
            previous_record=read_json(previous)
            check_identity(previous_record,latest,signer)
            if previous_record.get('signingCertificateSha256')!=signer:fail('signer_changed')
            previous_semantic=numeric_version(latest.get('versionName'))
            if latest['versionCode']<version and previous_semantic is not None:
                if semantic is None or semantic<=previous_semantic:fail('version_name_not_increasing')
        target=root/'apks'/str(version)
        target_apk=target/('deepseek-continuation-'+str(version)+'.apk')
        if target.exists():
            if (target.is_symlink() or (target/'.release.json').is_symlink()
                    or not (target/'.release.json').is_file() or not target_apk.is_file()):
                fail('version_directory_unmanaged')
            manifest=check_identity(read_json(target/'.release.json'),manifest,signer)
            if target_apk.is_symlink() or sha(target_apk)!=manifest['sha256']:fail('existing_apk_hash_conflict')
        else:
            package.chmod(0o644)
            os.replace(package,stage/target_apk.name)
            record={'publisherFormat':'continuation-release-v1','manifest':manifest,'signingCertificateSha256':signer}
            with (stage/'.release.json').open('x',encoding='utf-8') as stream:
                json.dump(record,stream,ensure_ascii=False,indent=2);stream.flush();os.fsync(stream.fileno())
            (stage/'.release.json').chmod(0o600)
            stage.chmod(0o755)
            os.rename(stage,target)
        if latest is not None and latest['versionCode']==version:
            for key in ('packageName','versionCode','versionName','apkUrl','apkSize','sha256','minSdk'):
                if latest.get(key)!=manifest[key]:fail('latest_same_version_conflict')
            print(json.dumps({'ok':True,'status':'already_published','manifest':latest}))
        else:
            encoded=(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n').encode('utf-8')
            if len(encoded)>32768:fail('manifest_too_large')
            temporary=root/('.latest-'+token+'.tmp')
            with temporary.open('xb') as stream:
                stream.write(encoded);stream.flush();os.fsync(stream.fileno())
            temporary.chmod(0o644)
            os.replace(temporary,latest_path)
            print(json.dumps({'ok':True,'status':'published','manifest':manifest}))
except Exception as error:
    print(json.dumps({'ok':False,'error':str(error) if isinstance(error,RuntimeError) else 'release_commit_failed'}))
    raise SystemExit(1)
finally:
    if re.fullmatch(r'[0-9a-f]{32}',token) and stage.exists() and not stage.is_symlink() and stage.parent.resolve()==(root/'.staging').resolve():
        shutil.rmtree(stage)
    temporary=root/('.latest-'+token+'.tmp')
    if re.fullmatch(r'[0-9a-f]{32}',token) and temporary.is_file() and not temporary.is_symlink():
        temporary.unlink()
'''


CLEAN_STAGE = r'''
import re,shutil
from pathlib import Path
root=Path('/var/www/deepseek-continuation-updates/.staging')
stage=root/PAYLOAD['stage']
if re.fullmatch(r'[0-9a-f]{32}',stage.name) and stage.exists() and not stage.is_symlink() and stage.parent.resolve()==root.resolve():
    shutil.rmtree(stage)
print(json.dumps({'ok':True,'status':'stage_cleaned'}))
'''


def prepare_hosting(client):
    return remote_python(client, PREPARE_HOSTING, {"snippet": NGINX_SNIPPET})


def publish(client, apk_path, manifest, signer):
    stage = uuid.uuid4().hex
    remote_python(client, CREATE_STAGE, {"stage": stage})
    try:
        sftp = client.open_sftp()
        try:
            sftp.put(str(Path(apk_path).resolve(strict=True)), REMOTE_ROOT + "/.staging/" + stage + "/package.apk")
            sftp.chmod(REMOTE_ROOT + "/.staging/" + stage + "/package.apk", 0o600)
        finally:
            sftp.close()
        return remote_python(client, COMMIT_RELEASE, {"stage": stage, "manifest": manifest, "signer": signer})
    except Exception:
        try:
            remote_python(client, CLEAN_STAGE, {"stage": stage})
        except Exception:
            pass
        raise


def main(argv=None):
    parser = argparse.ArgumentParser(description="验证并发布续聊助手静态 APK 更新")
    commands = parser.add_subparsers(dest="command", required=True)
    hosting = commands.add_parser("prepare-hosting")
    hosting.add_argument("--ssh-helper", required=True)
    release = commands.add_parser("publish")
    release.add_argument("--apk", required=True)
    release.add_argument("--verified-metadata", required=True)
    release.add_argument("--changelog-file")
    release.add_argument("--ssh-helper")
    release.add_argument("--dry-run", action="store_true")
    args = parser.parse_args(argv)
    client = None
    try:
        if args.command == "prepare-hosting":
            client = connect(args.ssh_helper)
            result = prepare_hosting(client)
        else:
            notes = read_json(args.changelog_file) if args.changelog_file else []
            manifest, signer = prepare_release(args.apk, args.verified_metadata, notes)
            if args.dry_run:
                result = {"ok": True, "status": "dry_run", "manifest": manifest,
                          "signature_assertion_bound_to_apk": True, "remote_checked": False}
            else:
                client = connect(args.ssh_helper)
                result = publish(client, args.apk, manifest, signer)
        print(json.dumps(result, ensure_ascii=True, separators=(",", ":")))
        return 0
    except PublishError as error:
        print(json.dumps({"ok": False, "error": error.code}))
        return 2
    except Exception:
        print(json.dumps({"ok": False, "error": "publication_failed"}))
        return 2
    finally:
        if client is not None:
            client.close()


if __name__ == "__main__":
    sys.exit(main())
