#!/usr/bin/env python3
"""
Check that every class, field and method our classes reference exists in the
BD-J platform API (a stubs jar/zip such as HD Cookbook's enhanced-stubs.zip).

    python3 apicheck.py <platform-stubs.zip> <classes-dir>

Exit status 1 if anything is missing.
"""
import os
import sys
import zipfile

from classfile import ClassFile, CP_CLASS, CP_FIELD, CP_METHOD, CP_IMETHOD


class Info:
    def __init__(self, cf):
        self.name = cf.name()
        self.super = cf.super_name()
        self.interfaces = [cf.class_name(i) for i in cf.interfaces]
        self.fields = {(cf.utf(f[1]), cf.utf(f[2])) for f in cf.fields}
        self.methods = {(cf.utf(m[1]), cf.utf(m[2])) for m in cf.methods}


def load_platform(path):
    out = {}
    with zipfile.ZipFile(path) as z:
        for n in z.namelist():
            if n.endswith(".class"):
                info = Info(ClassFile(z.read(n)))
                out[info.name] = info
    return out


def load_dir(path):
    out, files = {}, {}
    for root, _, fs in os.walk(path):
        for f in fs:
            if f.endswith(".class"):
                cf = ClassFile(open(os.path.join(root, f), "rb").read())
                out[cf.name()] = Info(cf)
                files[cf.name()] = cf
    return out, files


def lookup(classes, owner, name, desc, kind):
    seen, todo = set(), [owner]
    while todo:
        c = todo.pop(0)
        if c in seen or c is None:
            continue
        seen.add(c)
        info = classes.get(c)
        if info is None:
            return False
        if (name, desc) in (info.fields if kind == "field" else info.methods):
            return True
        todo.append(info.super)
        todo.extend(info.interfaces)
    return False


def main():
    platform = load_platform(sys.argv[1])
    ours, files = load_dir(sys.argv[2])
    classes = dict(platform)
    classes.update(ours)
    missing = set()
    for cname, cf in files.items():
        for i, e in enumerate(cf.cp):
            if not e:
                continue
            if e[0] == CP_CLASS:
                n = cf.class_name(i).lstrip("[")
                if n.startswith("L"):
                    n = n[1:-1]
                if len(n) > 1 and n not in classes:
                    missing.add("class  %s  (used by %s)" % (n, cname))
            elif e[0] in (CP_FIELD, CP_METHOD, CP_IMETHOD):
                owner, name, desc = cf.ref(i)
                if owner.startswith("["):
                    continue                      # array methods (clone etc.)
                kind = "field" if e[0] == CP_FIELD else "method"
                if owner not in classes:
                    missing.add("class  %s  (used by %s)" % (owner, cname))
                elif not lookup(classes, owner, name, desc, kind):
                    missing.add("%s %s.%s%s  (used by %s)" % (kind, owner, name, desc, cname))
    print("apicheck: %d classes checked against %d platform classes" % (len(files), len(platform)))
    if missing:
        print("MISSING from the BD-J platform:")
        for m in sorted(missing):
            print("  " + m)
        sys.exit(1)
    print("apicheck: OK - everything referenced exists in the BD-J API")


if __name__ == "__main__":
    main()
