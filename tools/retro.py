#!/usr/bin/env python3
"""
Convert class files compiled with `javac -source 8 -target 8` into Java 1.3
class files (version 47.0) for BD-J players.

    python3 retro.py <classes-dir>        (rewrites in place)

What it changes:
  * class file version 52 -> 47
  * java/lang/StringBuilder -> java/lang/StringBuffer (javac uses StringBuilder
    for string concatenation; BD-J only has StringBuffer, same method shapes)
  * drops StackMapTable attributes (only meaningful for version >= 50)

What it refuses (the source must avoid these):
  * invokedynamic / method handles (lambdas etc.)
  * ldc of a class constant (class literals like Foo.class)
"""
import os
import sys

from classfile import ClassFile, split_code, join_code, walk, NEW_TAGS, CP_UTF8, CP_CLASS

TARGET_MAJOR = 47


def convert(path):
    cf = ClassFile(open(path, "rb").read())
    problems = []
    for i, e in enumerate(cf.cp):
        if e and e[0] in NEW_TAGS:
            problems.append("constant pool has %s" % NEW_TAGS[e[0]])
    # rename StringBuilder
    for e in cf.cp:
        if e and e[0] == CP_UTF8 and "java/lang/StringBuilder" in e[1]:
            e[1] = e[1].replace("java/lang/StringBuilder", "java/lang/StringBuffer")
    # strip StackMapTable, check bytecode
    for m, attr in cf.code_attrs():
        ms, ml, code, exc, attrs = split_code(attr[1])
        attrs = [a for a in attrs if cf.utf(a[0]) != "StackMapTable"]
        for pc, op, arg in walk(code):
            if op == 0xba:
                problems.append("%s: invokedynamic" % cf.utf(m[1]))
            if op in (0x12, 0x13):
                idx = arg[0] if op == 0x12 else (arg[0] << 8 | arg[1])
                if cf.cp[idx][0] == CP_CLASS:
                    problems.append("%s: class literal (ldc Class %s)"
                                    % (cf.utf(m[1]), cf.class_name(idx)))
        attr[1] = join_code(ms, ml, code, exc, attrs)
    if problems:
        raise SystemExit("%s:\n  %s" % (path, "\n  ".join(problems)))
    cf.major, cf.minor = TARGET_MAJOR, 0
    open(path, "wb").write(cf.to_bytes())


def main():
    n = 0
    for root, _, files in os.walk(sys.argv[1]):
        for f in files:
            if f.endswith(".class"):
                convert(os.path.join(root, f))
                n += 1
    print("retro: converted %d class files to version %d.0" % (n, TARGET_MAJOR))


if __name__ == "__main__":
    main()
