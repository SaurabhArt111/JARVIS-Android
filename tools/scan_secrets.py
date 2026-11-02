#!/usr/bin/env python3
"""Fails (exit 1) if the project tree contains something that looks like a real credential. Run before zipping or committing:  python3 -I tools/scan_secrets.py [root]

Looks for well-known key shapes (Groq gsk_, OpenAI sk-, Hugging Face hf_, Google AIza, AWS AKIA, private-key blocks, 'Bearer <long token>', key = "long literal") and for
files that must never be shipped (.env, keystores, local.properties, downloaded .onnx models). Strings that are obviously test fixtures (they contain SECRET, supersecret,
abcdefghijklmnop, EXAMPLE, PLACEHOLDER or are all one repeated character) are allowed; the unit tests use them to prove that logs and exports redact keys."""
import os, re, sys

root = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
PATTERNS = {
    "Groq key": rb"gsk_[A-Za-z0-9]{16,}", "OpenAI-style key": rb"sk-[A-Za-z0-9_-]{16,}", "Hugging Face token": rb"hf_[A-Za-z0-9]{16,}",
    "Google API key": rb"AIza[0-9A-Za-z_-]{30,}", "AWS access key": rb"AKIA[0-9A-Z]{16}", "private key block": rb"-----BEGIN [A-Z ]*PRIVATE KEY-----",
    "bearer token": rb"Bearer [A-Za-z0-9._-]{25,}",
    "key assignment": rb"(?i)(?:api[_-]?key|secret|token|password)\s*[:=]\s*[\"'][A-Za-z0-9/+_-]{24,}[\"']",
}
FIXTURE = re.compile(rb"SECRET|supersecret|abcdefghijklmnop|EXAMPLE|PLACEHOLDER|REDACTED|x{8,}|X{8,}|0{8,}|1234567890", re.I)
FORBIDDEN_NAMES = (".env", "local.properties", "secrets.properties")
FORBIDDEN_EXT = (".keystore", ".jks", ".onnx", ".pem", ".p12")
SKIP_DIRS = {".git", ".gradle", "build", ".idea"}
problems = []
for dp, dn, fn in os.walk(root):
    dn[:] = [d for d in dn if d not in SKIP_DIRS]
    for f in fn:
        p = os.path.join(dp, f); rel = os.path.relpath(p, root)
        if f in FORBIDDEN_NAMES or (f.startswith(".env") and f != ".env.example") or f.lower().endswith(FORBIDDEN_EXT):
            problems.append(f"{rel}: file type must not be shipped"); continue
        try: data = open(p, "rb").read()
        except OSError: continue
        if b"\x00" in data[:4096] and not f.endswith((".gz", ".bin")): continue
        for name, rx in PATTERNS.items():
            for m in re.finditer(rx, data):
                if FIXTURE.search(m.group(0)): continue
                problems.append(f"{rel}: looks like a {name} ({m.group(0)[:5].decode('latin1')}...)")
if problems:
    print("POSSIBLE SECRETS / FORBIDDEN FILES:"); [print(" -", x) for x in problems]; sys.exit(1)
print(f"secrets scan OK ({root})")
