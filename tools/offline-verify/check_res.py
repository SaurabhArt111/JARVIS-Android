#!/usr/bin/env python3
"""Offline static checks for the Android resources/manifest (no aapt2 available in this sandbox).
 - every XML under res/ and the manifest is well-formed
 - every @type/name reference inside res/ and the manifest resolves to a declared resource (or android: framework resource)
 - every component/class named in the manifest exists in the compiled output (pass compiled classes dir)
 - every R.<type>.<name> used in Kotlin sources is declared
usage: check_res.py <app_dir> <compiled_classes_dir>
"""
import os, re, sys, xml.etree.ElementTree as ET

app, classes = sys.argv[1], sys.argv[2]
res = os.path.join(app, "src", "main", "res")
manifest = os.path.join(app, "src", "main", "AndroidManifest.xml")
errors = []

declared = {}
def add(t, n): declared.setdefault(t, set()).add(n.replace(".", "_"))
VAL = {"string": "string", "color": "color", "dimen": "dimen", "integer": "integer", "bool": "bool", "style": "style", "string-array": "array",
       "integer-array": "array", "array": "array", "plurals": "plurals", "attr": "attr", "declare-styleable": "styleable"}
xml_files = [manifest]
for d in sorted(os.listdir(res)) if os.path.isdir(res) else []:
    p = os.path.join(res, d)
    if not os.path.isdir(p): continue
    t = d.split("-")[0]
    for f in os.listdir(p):
        fp = os.path.join(p, f)
        if f.endswith(".xml"): xml_files.append(fp)
        if t == "values":
            try: root = ET.parse(fp).getroot()
            except Exception as e: errors.append(f"{fp}: {e}"); continue
            for el in root:
                k = el.get("type") if el.tag == "item" else VAL.get(el.tag)
                if k and el.get("name"): add(k, el.get("name"))
        else:
            add(t, os.path.splitext(f)[0])
for fp in xml_files:
    try: txt = open(fp, encoding="utf-8").read(); ET.fromstring(txt)
    except Exception as e: errors.append(f"{fp}: not well-formed: {e}"); continue
    for m in re.finditer(r'@\+id/([\w.]+)', txt): add("id", m.group(1))
for fp in xml_files:
    txt = open(fp, encoding="utf-8").read()
    for m in re.finditer(r'(?<![\w])@(?!\+|android:)(?:\*)?(\w+)/([\w.]+)(\*?)', txt):
        t, n = m.group(1), m.group(2).replace(".", "_")
        if t in ("null", "empty", "undefined"): continue
        if m.group(3) == "*":   # tools:keep / tools:discard wildcard (e.g. @drawable/ic_*): must match at least one declared resource
            if not any(x.startswith(n) for x in declared.get(t, set())): errors.append(f"{os.path.relpath(fp, app)}: wildcard @{t}/{n}* matches nothing")
            continue
        if n not in declared.get(t, set()): errors.append(f"{os.path.relpath(fp, app)}: unresolved @{t}/{n}")
    for m in re.finditer(r'\?(?!android:)attr/([\w.]+)', txt):
        pass

# manifest classes
mt = ET.parse(manifest).getroot()
pkg = None
ns = "{http://schemas.android.com/apk/res/android}"
ns_pkg = os.environ.get("APP_NAMESPACE", "com.jarvis.assistant")
def cls_exists(name):
    if name.startswith("."): name = ns_pkg + name
    elif "." not in name: name = ns_pkg + "." + name
    return os.path.exists(os.path.join(classes, *name.split(".")) + ".class")
for tag in ("application", "activity", "activity-alias", "service", "receiver", "provider"):
    for el in mt.iter(tag):
        n = el.get(ns + "name")
        if n and not n.startswith("android.") and not cls_exists(n): errors.append(f"manifest <{tag}> class not found: {n}")
        t = el.get(ns + "targetActivity")
        if t and not cls_exists(t): errors.append(f"manifest alias target not found: {t}")

# R usage in Kotlin
for root_dir, _, files in os.walk(os.path.join(app, "src")):
    for f in files:
        if not f.endswith(".kt"): continue
        fp = os.path.join(root_dir, f)
        txt = open(fp, encoding="utf-8").read()
        for m in re.finditer(r'(?<![\w.])R\.(\w+)\.(\w+)', txt):
            t, n = m.group(1), m.group(2)
            if t in ("string", "drawable", "color", "xml", "mipmap", "layout", "dimen", "style", "id", "raw", "array", "anim", "bool", "integer", "plurals") and n not in declared.get(t, set()):
                errors.append(f"{os.path.relpath(fp, app)}: R.{t}.{n} not declared")
if errors:
    print("\n".join(sorted(set(errors)))); print(f"{len(set(errors))} problem(s)"); sys.exit(1)
print("resource/manifest checks passed")
