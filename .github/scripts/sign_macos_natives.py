#!/usr/bin/env python3
"""Developer ID-sign the macOS native libraries inside a JAR or ZIP, in place.

Apple's notary service looks inside archives in an app bundle, however deeply
nested, and rejects any Mach-O binary that is not signed with a Developer ID
and timestamped. The Commander JAR carries several (JNA, FlatLaf, osxkeychain,
pty4j), and plugin ZIPs carry more inside their bundled JARs (LWJGL, JNA). The
JVM extracts and loads them at runtime, so they are signed when the artifact is
built, before its detached .sig is computed.

Every Mach-O entry is re-signed, including ones a third party already signed,
so the artifact carries one consistent identity. Nested .jar and .zip entries
are searched and rewritten the same way. Signing uses rcodesign, which runs on
Linux; entry order, timestamps and compression are preserved.

The identity is a PEM file holding only the Developer ID certificate and its
key. Given a .p12 that also carries Apple's intermediate CA, rcodesign may
sign with the intermediate instead, which notarization rejects.

usage: sign_jar_natives.py <jar-or-zip> <rcodesign> <identity.pem>
"""

import io
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile

MACH_O_MAGIC = {
    b"\xcf\xfa\xed\xfe",  # 64-bit
    b"\xce\xfa\xed\xfe",  # 32-bit
    b"\xca\xfe\xba\xbe",  # universal (also the class-file magic, hence the .class check)
    b"\xfe\xed\xfa\xcf",
}


def is_mach_o(name, data):
    return data[:4] in MACH_O_MAGIC and not name.endswith(".class")


class Signer:
    def __init__(self, rcodesign, identity, work):
        self.rcodesign = rcodesign
        self.identity = identity
        self.work = work
        self.count = 0

    def sign_binary(self, label, data):
        path = os.path.join(self.work, str(self.count), os.path.basename(label))
        os.makedirs(os.path.dirname(path))
        with open(path, "wb") as f:
            f.write(data)
        subprocess.run(
            [self.rcodesign, "sign", "--pem-file", self.identity, "--for-notarization", path],
            check=True, stdout=subprocess.DEVNULL)
        self.count += 1
        print(f"signed {label}")
        with open(path, "rb") as f:
            return f.read()

    def sign_archive(self, label, source):
        """Returns the rewritten archive bytes, or None when nothing in it changed."""
        changed = {}
        with zipfile.ZipFile(source) as archive:
            for info in archive.infolist():
                if info.is_dir():
                    continue
                data = archive.read(info)
                entry = f"{label}!{info.filename}"
                if is_mach_o(info.filename, data):
                    changed[info.filename] = self.sign_binary(entry, data)
                elif info.filename.lower().endswith((".jar", ".zip")):
                    nested = self.sign_archive(entry, io.BytesIO(data))
                    if nested is not None:
                        changed[info.filename] = nested
            if not changed:
                return None
            out = io.BytesIO()
            with zipfile.ZipFile(out, "w") as target:
                for info in archive.infolist():
                    data = changed.get(info.filename)
                    if data is None:
                        data = archive.read(info)
                    target.writestr(info, data, compress_type=info.compress_type)
            return out.getvalue()


def main():
    if len(sys.argv) != 4:
        sys.exit(__doc__.strip().splitlines()[-1])
    path, rcodesign, identity = sys.argv[1:]

    work = tempfile.mkdtemp(prefix="nuclr-macos-natives-")
    try:
        signer = Signer(rcodesign, identity, work)
        rewritten = signer.sign_archive(os.path.basename(path), path)
        if rewritten is None:
            sys.exit(f"no Mach-O binaries found in {path}")
        with open(path, "wb") as f:
            f.write(rewritten)
        print(f"{signer.count} native libraries signed in {path}")
    finally:
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    main()
