#!/usr/bin/env python3
"""
Minimal Java class-file reader/writer used by retro.py and apicheck.py.
Handles the constant pool, fields, methods, attributes and bytecode walking.
"""
import struct

CP_UTF8, CP_INT, CP_FLOAT, CP_LONG, CP_DOUBLE = 1, 3, 4, 5, 6
CP_CLASS, CP_STRING, CP_FIELD, CP_METHOD, CP_IMETHOD, CP_NAT = 7, 8, 9, 10, 11, 12
NEW_TAGS = {15: "MethodHandle", 16: "MethodType", 17: "Dynamic", 18: "InvokeDynamic",
            19: "Module", 20: "Package"}


class ClassFile:
    def __init__(self, data):
        self.d = data
        self.p = 0
        assert self.u4() == 0xCAFEBABE, "not a class file"
        self.minor, self.major = self.u2(), self.u2()
        n = self.u2()
        self.cp = [None] * n
        i = 1
        while i < n:
            tag = self.u1()
            if tag == CP_UTF8:
                ln = self.u2()
                self.cp[i] = [tag, self.d[self.p:self.p + ln].decode("utf-8", "surrogatepass")]
                self.p += ln
            elif tag in (CP_INT, CP_FLOAT):
                self.cp[i] = [tag, self.raw(4)]
            elif tag in (CP_LONG, CP_DOUBLE):
                self.cp[i] = [tag, self.raw(8)]
                i += 1
            elif tag in (CP_CLASS, CP_STRING, 16, 19, 20):
                self.cp[i] = [tag, self.u2()]
            elif tag in (CP_FIELD, CP_METHOD, CP_IMETHOD, CP_NAT, 17, 18):
                self.cp[i] = [tag, self.u2(), self.u2()]
            elif tag == 15:
                self.cp[i] = [tag, self.u1(), self.u2()]
            else:
                raise ValueError("bad constant tag %d" % tag)
            i += 1
        self.access, self.this, self.super = self.u2(), self.u2(), self.u2()
        self.interfaces = [self.u2() for _ in range(self.u2())]
        self.fields = [self.member() for _ in range(self.u2())]
        self.methods = [self.member() for _ in range(self.u2())]
        self.attrs = self.attributes()

    # -- reading
    def u1(self):
        v = self.d[self.p]; self.p += 1; return v

    def u2(self):
        v = struct.unpack_from(">H", self.d, self.p)[0]; self.p += 2; return v

    def u4(self):
        v = struct.unpack_from(">I", self.d, self.p)[0]; self.p += 4; return v

    def raw(self, n):
        v = self.d[self.p:self.p + n]; self.p += n; return v

    def attributes(self):
        out = []
        for _ in range(self.u2()):
            name = self.u2()
            ln = self.u4()
            out.append([name, self.raw(ln)])
        return out

    def member(self):
        return [self.u2(), self.u2(), self.u2(), self.attributes()]   # access, name, desc, attrs

    # -- helpers
    def utf(self, i):
        return self.cp[i][1]

    def class_name(self, i):
        return self.utf(self.cp[i][1])

    def name_and_type(self, i):
        e = self.cp[i]
        return self.utf(e[1]), self.utf(e[2])

    def ref(self, i):
        """(owner, name, desc) for a Field/Method/InterfaceMethod ref."""
        e = self.cp[i]
        n, d = self.name_and_type(e[2])
        return self.class_name(e[1]), n, d

    def attr_name(self, a):
        return self.utf(a[0])

    def name(self):
        return self.class_name(self.this)

    def super_name(self):
        return self.class_name(self.super) if self.super else None

    def code_attrs(self):
        """Yield (method, code-attribute) pairs."""
        for m in self.methods:
            for a in m[3]:
                if self.attr_name(a) == "Code":
                    yield m, a

    # -- writing
    def to_bytes(self):
        o = bytearray()
        o += struct.pack(">IHHH", 0xCAFEBABE, self.minor, self.major, len(self.cp))
        i = 1
        while i < len(self.cp):
            e = self.cp[i]
            tag = e[0]
            o.append(tag)
            if tag == CP_UTF8:
                b = e[1].encode("utf-8", "surrogatepass")
                o += struct.pack(">H", len(b)) + b
            elif tag in (CP_INT, CP_FLOAT, CP_LONG, CP_DOUBLE):
                o += e[1]
            elif tag == 15:
                o += struct.pack(">BH", e[1], e[2])
            else:
                for v in e[1:]:
                    o += struct.pack(">H", v)
            i += 2 if tag in (CP_LONG, CP_DOUBLE) else 1
        o += struct.pack(">HHH", self.access, self.this, self.super)
        o += struct.pack(">H", len(self.interfaces))
        for x in self.interfaces:
            o += struct.pack(">H", x)
        for group in (self.fields, self.methods):
            o += struct.pack(">H", len(group))
            for acc, nm, ds, at in group:
                o += struct.pack(">HHH", acc, nm, ds) + self.write_attrs(at)
        o += self.write_attrs(self.attrs)
        return bytes(o)

    @staticmethod
    def write_attrs(attrs):
        o = bytearray(struct.pack(">H", len(attrs)))
        for name, body in attrs:
            o += struct.pack(">HI", name, len(body)) + body
        return bytes(o)


# --- Code attribute helpers

def split_code(body):
    max_stack, max_locals, code_len = struct.unpack_from(">HHI", body, 0)
    code = body[8:8 + code_len]
    p = 8 + code_len
    n_exc = struct.unpack_from(">H", body, p)[0]
    exc = body[p + 2:p + 2 + 8 * n_exc]
    p += 2 + 8 * n_exc
    n_attr = struct.unpack_from(">H", body, p)[0]
    p += 2
    attrs = []
    for _ in range(n_attr):
        nm, ln = struct.unpack_from(">HI", body, p)
        attrs.append([nm, body[p + 6:p + 6 + ln]])
        p += 6 + ln
    return max_stack, max_locals, code, exc, attrs


def join_code(max_stack, max_locals, code, exc, attrs):
    o = bytearray(struct.pack(">HHI", max_stack, max_locals, len(code)))
    o += code
    o += struct.pack(">H", len(exc) // 8) + exc
    o += ClassFile.write_attrs(attrs)
    return bytes(o)


# opcode -> operand byte count (excluding tableswitch/lookupswitch/wide)
_LEN = {}
for op in range(256):
    _LEN[op] = 0
for op in (0x10, 0x12, 0x15, 0x16, 0x17, 0x18, 0x19, 0x36, 0x37, 0x38, 0x39, 0x3a, 0xa9, 0xbc):
    _LEN[op] = 1
for op in (0x11, 0x13, 0x14, 0x84, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xbb, 0xbd, 0xc0, 0xc1,
           0xc6, 0xc7) + tuple(range(0x99, 0xa9)):
    _LEN[op] = 2
for op in (0xc5,):
    _LEN[op] = 3
for op in (0xb9, 0xba, 0xc8, 0xc9):
    _LEN[op] = 4


def walk(code):
    """Yield (pc, opcode, operand-bytes)."""
    pc = 0
    while pc < len(code):
        op = code[pc]
        if op == 0xaa:                       # tableswitch
            p = (pc + 4) & ~3
            lo, hi = struct.unpack_from(">ii", code, p + 4)
            end = p + 12 + 4 * (hi - lo + 1)
        elif op == 0xab:                     # lookupswitch
            p = (pc + 4) & ~3
            n = struct.unpack_from(">i", code, p + 4)[0]
            end = p + 8 + 8 * n
        elif op == 0xc4:                     # wide
            end = pc + (6 if code[pc + 1] == 0x84 else 4)
        else:
            end = pc + 1 + _LEN[op]
        yield pc, op, code[pc + 1:end]
        pc = end
