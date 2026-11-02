#!/usr/bin/env python3
"""Generate ephemeral OWNED fixture material; never display or package private values."""
import os, secrets, subprocess, tempfile
from pathlib import Path
root = Path(__file__).resolve().parents[1]
java_home = Path(os.environ["JAVA_HOME"])
with tempfile.TemporaryDirectory(prefix="phonoteka-owned-tls-") as temporary:
    local = Path(temporary); env = os.environ.copy()
    env.update(PHONOTEKA_MOCK_KEYSTORE=str(local / "fixture.p12"),
        PHONOTEKA_MOCK_KEYSTORE_PASSWORD=secrets.token_hex(24), PHONOTEKA_MOCK_HMAC_KEY=secrets.token_hex(32))
    generated = subprocess.run([str(java_home / "bin/keytool"), "-genkeypair", "-alias", "owned-fixture", "-keyalg", "RSA",
        "-keysize", "2048", "-validity", "2", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
        "-storetype", "PKCS12", "-keystore", env["PHONOTEKA_MOCK_KEYSTORE"],
        "-storepass:env", "PHONOTEKA_MOCK_KEYSTORE_PASSWORD", "-keypass:env", "PHONOTEKA_MOCK_KEYSTORE_PASSWORD"],
        env=env, capture_output=True, timeout=60)
    if generated.returncode: raise SystemExit("Ephemeral fixture generation failed; private values withheld")
    files = ["LocalTlsPolicy", "LocalLab15MockServer", "LocalLab15MockServerTest", "LocalLab15WssServer", "LocalLab15WssServerTest", "LocalLab15HttpsContractTest", "LocalLab15TlsDowngradeTest"]
    subprocess.run([str(java_home / "bin/javac"), "-d", str(local)] + [str(root / "tools" / (name + ".java")) for name in files], check=True, timeout=60)
    for test in ["LocalLab15MockServerTest", "LocalLab15WssServerTest", "LocalLab15HttpsContractTest", "LocalLab15TlsDowngradeTest"]:
        subprocess.run([str(java_home / "bin/java"), "-cp", str(local), test], env=env, check=True, timeout=60)
print("Ephemeral private fixture material deleted; no real accounts/keys/network used")
