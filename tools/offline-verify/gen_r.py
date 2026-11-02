#!/usr/bin/env python3
"""Generate stub R.java + BuildConfig.java from app/src/main/res so Kotlin can be compile-checked WITHOUT aapt2/AGP.
This is a verification aid only; the real R class is produced by the Android Gradle Plugin.
usage: gen_r.py <app_dir> <out_dir> <package> <version_name>
"""
import os, re, sys, xml.etree.ElementTree as ET

app, out, pkg, vname = sys.argv[1:5]
res = os.path.join(app, "src", "main", "res")
ids = {}  # type -> set(names)
def add(t, n):
    n = n.replace(".", "_")
    ids.setdefault(t, set()).add(n)

VALUE_TAGS = {"string": "string", "color": "color", "dimen": "dimen", "integer": "integer", "bool": "bool", "style": "style",
              "string-array": "array", "integer-array": "array", "array": "array", "plurals": "plurals", "attr": "attr", "declare-styleable": "styleable", "item": None}
if os.path.isdir(res):
    for d in sorted(os.listdir(res)):
        p = os.path.join(res, d)
        if not os.path.isdir(p):
            continue
        t = d.split("-")[0]
        for f in os.listdir(p):
            fp = os.path.join(p, f)
            if t == "values":
                try:
                    root = ET.parse(fp).getroot()
                except Exception as e:
                    print("XML ERROR", fp, e); sys.exit(2)
                for el in root:
                    kind = VALUE_TAGS.get(el.tag)
                    if el.tag == "item" and el.get("type"):
                        kind = el.get("type")
                    if kind and el.get("name"):
                        add(kind, el.get("name"))
            else:
                add(t, os.path.splitext(f)[0])
                if f.endswith(".xml"):
                    try:
                        txt = open(fp, encoding="utf-8").read()
                    except Exception:
                        continue
                    for m in re.finditer(r'@\+id/([A-Za-z0-9_.]+)', txt):
                        add("id", m.group(1))
os.makedirs(os.path.join(out, *pkg.split(".")), exist_ok=True)
n = 0x7f010000
lines = [f"package {pkg};", "public final class R {"]
for t in sorted(ids):
    lines.append(f"  public static final class {t} {{")
    for name in sorted(ids[t]):
        lines.append(f"    public static final int {name} = 0x{n:08x};"); n += 1
    lines.append("  }")
lines.append("}")
open(os.path.join(out, *pkg.split("."), "R.java"), "w").write("\n".join(lines))
open(os.path.join(out, *pkg.split("."), "BuildConfig.java"), "w").write(
    f'package {pkg};\npublic final class BuildConfig {{\n  public static final boolean DEBUG = true;\n  public static final String APPLICATION_ID = "{pkg}";\n'
    f'  public static final String BUILD_TYPE = "debug";\n  public static final int VERSION_CODE = 1;\n  public static final String VERSION_NAME = "{vname}";\n}}\n')
print("R stub:", sum(len(v) for v in ids.values()), "resources")
