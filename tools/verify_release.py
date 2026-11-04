"""Bind Android SDK signature/package verification to one exact release APK."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

PACKAGE = 'app.banzhan.deepseekcontinuation'
CERTIFICATE = 'f7c2ad3513e196d9875d564e45d66b55bc6e46ca4ca32ab511a5c7ca7b63fda6'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--build-tools', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--expected-version-code', type=int, required=True)
    parser.add_argument('--expected-version-name', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        apk = args.apk.resolve(strict=True)
        env = {**os.environ, 'JAVA_HOME': str(args.java_home.resolve(strict=True))}
        extension = '.exe' if os.name == 'nt' else ''
        signer = args.build_tools / ('apksigner.bat' if os.name == 'nt' else 'apksigner')
        signed = subprocess.run([str(signer), 'verify', '--print-certs', str(apk)], env=env,
                                capture_output=True, timeout=60)
        if signed.returncode:
            raise ValueError('signature')
        certificates = re.findall(r'certificate SHA-256 digest: ([0-9a-fA-F]{64})', signed.stdout.decode('utf-8', errors='replace'))
        if [value.lower() for value in certificates] != [CERTIFICATE]:
            raise ValueError('certificate')
        package = subprocess.run([str(args.build_tools / ('aapt2' + extension)), 'dump', 'badging', str(apk)],
                                 env=env, capture_output=True, timeout=60)
        if package.returncode:
            raise ValueError('package')
        text = package.stdout.decode('utf-8', errors='strict')
        identity = re.search(r"(?m)^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']*)'", text)
        minimum = re.search(r"(?m)^(?:minSdkVersion|sdkVersion):'([0-9]+)'", text)
        if (identity is None or minimum is None or identity[1] != PACKAGE
                or int(identity[2]) != args.expected_version_code or identity[3] != args.expected_version_name
                or int(minimum[1]) != 26):
            raise ValueError('identity')
        digest = hashlib.sha256()
        with apk.open('rb') as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b''):
                digest.update(chunk)
        metadata = dict(packageName=PACKAGE, versionCode=int(identity[2]), versionName=identity[3], minSdk=26,
                        apkSha256=digest.hexdigest(), signatureVerified=True, signingCertificateSha256=CERTIFICATE)
        args.output.write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps(metadata, ensure_ascii=False))
        return 0
    except Exception:
        print('{"error":"release_verification_failed","metadata_written":false}', file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
