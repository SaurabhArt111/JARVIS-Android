#!/usr/bin/env python3
"""Runs the app's SQL (app/.../data/Sql.kt) against the real SQLite engine: creates the schema, prepares every Q_* statement, and exercises a few
of them with data (constraints, cascade delete, LIKE escaping, paging). Usage: check_sql.py <path to Sql.kt>"""
import re, sqlite3, sys

src = open(sys.argv[1], encoding="utf-8").read()

def kt_string(m):
    return m

# all raw ("""...""") and plain const strings, with $NAME templates expanded from private/const vals
consts = {}
for m in re.finditer(r'(?:private )?const val (\w+) =\s*((?:"(?:[^"\\]|\\.)*"\s*\+?\s*)+)', src):
    parts = re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(2))
    consts[m.group(1)] = "".join(parts)

def expand(s):
    for _ in range(5):
        s = re.sub(r'\$(\w+)', lambda x: consts.get(x.group(1), x.group(0)), s)
    return s.replace('\\\\', '\\')

consts = {k: expand(v) for k, v in consts.items()}
ddl_block = src[src.index("val CREATE"):src.index("val MIGRATIONS")]
ddl = [expand(t) if '$' in t else t for t in re.findall(r'"""(.*?)"""|"(CREATE [^"]*)"', ddl_block, re.S) and
       [a or b for a, b in re.findall(r'"""(.*?)"""|"(CREATE [^"]*)"', ddl_block, re.S)]]

db = sqlite3.connect(":memory:")
db.execute("PRAGMA foreign_keys=ON")
for stmt in ddl:
    db.execute(stmt)
print(f"schema ok: {len(ddl)} statements")

queries = {k: v for k, v in consts.items() if k.startswith("Q_")}
for name, q in sorted(queries.items()):
    n = q.count("?")
    db.execute("EXPLAIN " + q, [None] * n) if q.lstrip().upper().startswith("SELECT") else db.execute("EXPLAIN " + q, [None] * n)
print(f"prepared {len(queries)} queries")

def x(q, *a): return db.execute(consts[q], a)

# conversations / messages
cid = x("Q_CONV_INSERT", "", 1, 1).lastrowid
for i in range(5):
    x("Q_MSG_INSERT", cid, "user" if i % 2 == 0 else "assistant", f"hello 100% real_{i}", 10 + i, None, "")
x("Q_CONV_TITLE_IF_BLANK", "first", cid)
assert db.execute("SELECT title FROM conversations").fetchone()[0] == "first"
x("Q_CONV_TITLE_IF_BLANK", "second", cid)
assert db.execute("SELECT title FROM conversations").fetchone()[0] == "first", "title must only be set once"
page = x("Q_MSG_PAGE_LATEST", cid, 3).fetchall()
assert [r[0] for r in page] == [5, 4, 3], page
before = x("Q_MSG_PAGE_BEFORE", cid, 3, 10).fetchall()
assert [r[0] for r in before] == [2, 1], before
assert len(x("Q_MSG_SEARCH", "%100\\% real\\_3%", 10).fetchall()) == 1, "LIKE escaping"
assert len(x("Q_MSG_SEARCH", "%100\\%x%", 10).fetchall()) == 0
try:
    x("Q_MSG_INSERT", cid, "system", "x", 1, None, ""); raise SystemExit("role CHECK missing")
except sqlite3.IntegrityError: pass
try:
    x("Q_MSG_INSERT", 999, "user", "x", 1, None, ""); raise SystemExit("foreign key missing")
except sqlite3.IntegrityError: pass
db.execute("UPDATE messages SET reply_json=NULL")
x("Q_MSG_ATTACH_REPLY", '{"text":"hello 100% real_3"}', "voice", "hello 100% real_3")
assert db.execute("SELECT COUNT(*) FROM messages WHERE reply_json IS NOT NULL").fetchone()[0] == 1
assert x("Q_CONV_LIST").fetchall()[0][4] == 5
x("Q_CONV_DELETE", cid)
assert db.execute("SELECT COUNT(*) FROM messages").fetchone()[0] == 0, "cascade delete"
assert x("Q_CONV_LIST").fetchall() == []

# retention: prune old conversations, never the newest one
old1 = x("Q_CONV_INSERT", "old1", 100, 100).lastrowid
old2 = x("Q_CONV_INSERT", "old2", 200, 200).lastrowid
newest = x("Q_CONV_INSERT", "newest", 300, 300).lastrowid
x("Q_MSG_INSERT", old1, "user", "bye", 100, None, "")
assert x("Q_CONV_PRUNE", 250).rowcount == 2, "two old conversations go"
assert [r[0] for r in db.execute("SELECT id FROM conversations").fetchall()] == [newest]
assert db.execute("SELECT COUNT(*) FROM messages").fetchone()[0] == 0, "their messages go too"
assert x("Q_CONV_PRUNE", 10_000).rowcount == 0, "the newest conversation is never pruned, even if it is old"
db.execute("DELETE FROM conversations")

# reminders
x("Q_REM_INSERT", "r1", "call mom", "2026-10-25T17:00", "Asia/Kolkata", 1, "PENDING", 0, 1, None, None, "[]")
x("Q_REM_UPDATE", "call mom!", "2026-10-25T18:00", "Asia/Kolkata", 1, "FIRED", 0, 5, None, '["old"]', "r1")
r = x("Q_REM_GET", "r1").fetchone(); assert r[2] == "2026-10-25T18:00" and r[5] == "FIRED" and r[8] == 5, r
try:
    x("Q_REM_INSERT", "r2", "x", "2026-10-25T17:00", "UTC", 1, "BOGUS", 0, 1, None, None, "[]"); raise SystemExit("state CHECK missing")
except sqlite3.IntegrityError: pass
assert len(x("Q_REM_ALL").fetchall()) == 1
x("Q_REM_DELETE", "r1"); assert x("Q_REM_GET", "r1").fetchone() is None

# notes
x("Q_NOTE_INSERT", "n1", "buy milk", 1, 1, None, "[]"); x("Q_NOTE_UPDATE", "buy milk and eggs", 2, "r9", '["x"]', "n1")
assert x("Q_NOTE_GET", "n1").fetchone()[1] == "buy milk and eggs"; x("Q_NOTE_DELETE", "n1")

# files
x("Q_FILE_INSERT", "f1", "trip_plan.txt", "GENERATED_TEXT", "content://1", "text/plain", 10, 100, "PUBLIC_DOCUMENTS", "write a trip plan")
x("Q_FILE_INSERT", "f2", "fox.png", "IMAGES", "content://2", "image/png", 20, 200, "APP_PRIVATE", None)
assert len(x("Q_FILE_LIST_ALL").fetchall()) == 2 and x("Q_FILE_LIST_ALL").fetchall()[0][0] == "f2"
assert len(x("Q_FILE_LIST_CAT", "IMAGES").fetchall()) == 1
assert len(x("Q_FILE_LIST_Q", "%trip%", "%trip%").fetchall()) == 1
assert len(x("Q_FILE_LIST_CAT_Q", "IMAGES", "%trip%", "%trip%").fetchall()) == 0
assert x("Q_FILE_NAME_TAKEN", "IMAGES", "fox.png").fetchone() is not None and x("Q_FILE_NAME_TAKEN", "IMAGES", "cat.png").fetchone() is None
assert len(x("Q_FILE_OLDER_ALL", 150).fetchall()) == 1 and len(x("Q_FILE_OLDER_CAT", 500, "IMAGES").fetchall()) == 1

# timers
x("Q_TIMER_INSERT", "t1", "Pasta", 600, 999); assert x("Q_TIMER_ALL").fetchall() == [("t1", "Pasta", 600, 999)]
x("Q_TIMER_DELETE_ALL"); assert x("Q_TIMER_ALL").fetchall() == []
print("SQL OK")
