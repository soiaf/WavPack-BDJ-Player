#!/usr/bin/env python3
"""
BD-J certificates and jar signing, reproducing what HD Cookbook's
BDCertGenerator + BDSigner produce.

  python3 bdsign.py genkeys <keys-dir> <org-id-hex>
      Creates root + app (leaf) keys/certs:
        app.discroot.crt   -> goes in CERTIFICATE/ and CERTIFICATE/BACKUP/
        bu.discroot.crt    -> binding-unit root (also in CERTIFICATE/)
        *.key.pem          -> private keys. Keep them to re-sign later.

  python3 bdsign.py sign <keys-dir> <in.jar> <out.jar>
      Signs the jar as META-INF/SIG-BD00.{SF,RSA}:
        * SHA-1 digests in MANIFEST.MF and the .SF
        * .SF carries "BDJ-Signature-Version: 1.0" after Created-By and no
          main-attributes digest (this is what BDSigner's signWithBDJHeader does)
        * .RSA is a detached PKCS#7 SignedData, SHA1withRSA, no signed
          attributes, containing the leaf + root certificates

  python3 bdsign.py signbumf <keys-dir> <manifest.xml> <out.sf>
      Signs a binding unit manifest (VFS update) with the binding-unit key whose
      certificate is CERTIFICATE/bu.discroot.crt: SHA1withRSA over the file
      bytes, written as a DER BIT STRING (what HD Cookbook's BDSigner -bumf does).

  python3 bdsign.py verify <keys-dir> <signed.jar>
      Recomputes every digest and checks the PKCS#7 signature and chain.

Certificate profile copied byte-for-byte in structure from a BDCertGenerator
keystore known to work on hardware: RSA 1024, SHA1withRSA, UTF8String subject
"CN=..., OU=Codesigning Department, O=BDJCompany.<orgid>, C=US" (in that DER
order), GeneralizedTime validity year 1 .. 9999. Built with a small DER encoder
because current Python crypto libraries refuse SHA-1 certificate signing.
"""
import base64
import hashlib
import io
import os
import sys
import zipfile

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from cryptography.hazmat.primitives.serialization import pkcs7

CREATED_BY = "1.6.0 (Sun Microsystems Inc.)"
SIG_NAME = "SIG-BD00"


# ---------------------------------------------------------------- tiny DER encoder

def _len(n):
    if n < 0x80:
        return bytes([n])
    b = n.to_bytes((n.bit_length() + 7) // 8, "big")
    return bytes([0x80 | len(b)]) + b


def _tlv(tag, body):
    return bytes([tag]) + _len(len(body)) + body


def _seq(*parts):
    return _tlv(0x30, b"".join(parts))


def _set(*parts):
    return _tlv(0x31, b"".join(parts))


def _int(v):
    b = v.to_bytes(max(1, (v.bit_length() + 8) // 8), "big", signed=True)
    return _tlv(0x02, b)


def _oid(dotted):
    parts = [int(x) for x in dotted.split(".")]
    body = bytes([40 * parts[0] + parts[1]])
    for p in parts[2:]:
        enc = [p & 0x7f]
        p >>= 7
        while p:
            enc.insert(0, 0x80 | (p & 0x7f))
            p >>= 7
        body += bytes(enc)
    return _tlv(0x06, body)


NULL = b"\x05\x00"
OID_SHA1_RSA = "1.2.840.113549.1.1.5"
OID_RSA = "1.2.840.113549.1.1.1"
OID_SHA1 = "1.3.14.3.2.26"


def _alg(oid):
    return _seq(_oid(oid), NULL)


def _name(cn, org_id):
    """RDN order and UTF8String types exactly as in BDCertGenerator certificates."""
    rdns = (("2.5.4.3", cn), ("2.5.4.11", "Codesigning Department"),
            ("2.5.4.10", "BDJCompany." + org_id), ("2.5.4.6", "US"))
    return _seq(*[_set(_seq(_oid(o), _tlv(0x0c, v.encode()))) for o, v in rdns])


def _gtime(s):
    return _tlv(0x18, s.encode())


def _ext(oid, critical, value_der):
    parts = [_oid(oid)]
    if critical:
        parts.append(b"\x01\x01\xff")
    parts.append(_tlv(0x04, value_der))
    return _seq(*parts)


def _email(addr):
    return _seq(_tlv(0x81, addr.encode()))          # GeneralNames { rfc822Name }


KU_ROOT = bytes.fromhex("03020284")                   # digitalSignature + keyCertSign
KU_LEAF = bytes.fromhex("03020780")                   # digitalSignature
BC_CA = bytes.fromhex("30030101ff")

# Validity copied from the reference certificates (year 1 .. 9999), so a player
# with an unset clock never sees the certificate as "not yet valid".
ROOT_NOT_BEFORE, LEAF_NOT_BEFORE, NOT_AFTER = "00010202011007Z", "00010204011007Z", "99990201080000Z"


def _cert(subject, issuer, serial, not_before, pub_key, exts, sign_key):
    spki = pub_key.public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    tbs = _seq(_tlv(0xa0, _int(2)), _int(serial), _alg(OID_SHA1_RSA), issuer,
               _seq(_gtime(not_before), _gtime(NOT_AFTER)), subject, spki,
               _tlv(0xa3, _seq(*exts)))
    sig = sign_key.sign(tbs, padding.PKCS1v15(), hashes.SHA1())
    return _seq(tbs, _alg(OID_SHA1_RSA), _tlv(0x03, b"\x00" + sig))


def _serial():
    return int.from_bytes(os.urandom(4), "big") | 0x40000000


def genkeys(keydir, org_id):
    org_id = org_id.lower()
    assert len(org_id) == 8 and int(org_id, 16) >= 0, "org id must be 8 hex digits"
    os.makedirs(keydir, exist_ok=True)
    keys = dict((n, rsa.generate_private_key(public_exponent=65537, key_size=1024))
                for n in ("root", "app"))
    # One root for the program and for the binding-unit (library) manifest: some
    # players keep the disc's storage under a hash of one and check the other.
    keys["bu"] = keys["root"]
    studio = _name("Studio", org_id)
    root_exts = [_ext("2.5.29.15", True, KU_ROOT), _ext("2.5.29.18", False, _email("root@studio.com")),
                 _ext("2.5.29.19", True, BC_CA), _ext("2.5.29.17", False, _email("root@studio.com"))]
    root = _cert(studio, studio, _serial(), ROOT_NOT_BEFORE, keys["root"].public_key(), root_exts, keys["root"])
    bu = root
    leaf_exts = [_ext("2.5.29.15", True, KU_LEAF), _ext("2.5.29.17", False, _email("app@producer.com")),
                 _ext("2.5.29.18", False, _email("root@studio.com"))]
    leaf = _cert(_name("Producer", org_id), studio, _serial(), LEAF_NOT_BEFORE,
                 keys["app"].public_key(), leaf_exts, keys["root"])

    for n, k in keys.items():
        with open(os.path.join(keydir, n + ".key.pem"), "wb") as f:
            f.write(k.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                    serialization.NoEncryption()))
    for der, fn in ((root, "app.discroot.crt"), (bu, "bu.discroot.crt"), (leaf, "app.crt")):
        with open(os.path.join(keydir, fn), "wb") as f:
            f.write(der)
    with open(os.path.join(keydir, "orgid.txt"), "w") as f:
        f.write(org_id + "\n")
    print("genkeys: org id %s -> %s" % (org_id, keydir))


def _pkcs7_detached(sf, leaf, root, key):
    """PKCS#7 SignedData, SHA1withRSA over the .SF bytes, no signed attributes."""
    sig = key.sign(sf, padding.PKCS1v15(), hashes.SHA1())
    issuer_and_serial = _seq(leaf.issuer.public_bytes(), _int(leaf.serial_number))
    signer_info = _seq(_int(1), issuer_and_serial, _alg(OID_SHA1), _alg(OID_RSA), _tlv(0x04, sig))
    certs = leaf.public_bytes(serialization.Encoding.DER) + root.public_bytes(serialization.Encoding.DER)
    signed_data = _seq(_int(1), _set(_alg(OID_SHA1)), _seq(_oid("1.2.840.113549.1.7.1")),
                       _tlv(0xa0, certs), _set(signer_info))
    return _seq(_oid("1.2.840.113549.1.7.2"), _tlv(0xa0, signed_data))


def _load(keydir):
    def cert(fn):
        return x509.load_der_x509_certificate(open(os.path.join(keydir, fn), "rb").read())
    key = serialization.load_pem_private_key(open(os.path.join(keydir, "app.key.pem"), "rb").read(), None)
    return cert("app.discroot.crt"), cert("app.crt"), key


def _b64sha1(b):
    return base64.b64encode(hashlib.sha1(b).digest()).decode()


def _wrap72(line):
    """Manifest lines are max 72 bytes; continuation lines start with a space."""
    b = line.encode("utf-8")
    out = [b[:72]]
    b = b[72:]
    while b:
        out.append(b" " + b[:71])
        b = b[71:]
    return out


def _section(name, digest, eol):
    lines = _wrap72("Name: " + name) + [("SHA1-Digest: " + digest).encode()]
    return eol.join(lines) + eol + eol


def sign(keydir, src, dst):
    root, leaf, key = _load(keydir)
    zin = zipfile.ZipFile(src)
    entries = []
    for info in zin.infolist():
        n = info.filename
        if n.endswith("/") or n.upper().startswith("META-INF/"):
            continue                                  # drop old manifest/signatures and dirs
        entries.append((n, zin.read(n)))

    manifest = ("Manifest-Version: 1.0\r\nCreated-By: %s\r\n\r\n" % CREATED_BY).encode()
    sections = []
    for n, data in entries:
        sec = _section(n, _b64sha1(data), b"\r\n")
        sections.append((n, sec))
        manifest += sec

    sf = ("Signature-Version: 1.0\nCreated-By: %s\nBDJ-Signature-Version: 1.0\n"
          "SHA1-Digest-Manifest: %s\n\n" % (CREATED_BY, _b64sha1(manifest))).encode()
    for n, sec in sections:
        sf += _section(n, _b64sha1(sec), b"\n")

    rsa_blob = _pkcs7_detached(sf, leaf, root, key)

    def put(z, name, data):
        zi = zipfile.ZipInfo(name, date_time=(2008, 1, 1, 0, 0, 0))   # plain DOS-style entry
        zi.create_system = 0
        zi.external_attr = 0
        zi.compress_type = zipfile.ZIP_DEFLATED
        z.writestr(zi, data)

    with zipfile.ZipFile(dst, "w") as z:
        put(z, "META-INF/MANIFEST.MF", manifest)
        put(z, "META-INF/%s.SF" % SIG_NAME, sf)
        put(z, "META-INF/%s.RSA" % SIG_NAME, rsa_blob)
        for n, data in entries:
            put(z, n, data)
    print("sign: %s -> %s (%d entries, signer %s)" % (src, dst, len(entries), leaf.subject.rfc4514_string()))


def verify(keydir, jar):
    root, leaf, _ = _load(keydir)
    z = zipfile.ZipFile(jar)
    names = z.namelist()
    assert names[0] == "META-INF/MANIFEST.MF", "manifest must be first"
    manifest = z.read("META-INF/MANIFEST.MF")
    sf = z.read("META-INF/%s.SF" % SIG_NAME)
    blob = z.read("META-INF/%s.RSA" % SIG_NAME)
    text = sf.decode()
    assert "\nBDJ-Signature-Version: 1.0\n" in text, "missing BDJ header"
    assert "SHA1-Digest-Manifest: %s\n" % _b64sha1(manifest) in text, "manifest digest mismatch"

    # every class/resource listed, with matching digests
    msecs = manifest.split(b"\r\n\r\n")[1:]
    listed = {}
    for sec in msecs:
        if not sec.strip():
            continue
        lines = sec.decode().replace("\r\n ", "").split("\r\n")
        listed[lines[0][6:]] = (lines[1][13:], sec + b"\r\n\r\n")
    for n in names:
        if n.startswith("META-INF/"):
            continue
        assert n in listed, "unsigned entry " + n
        assert listed[n][0] == _b64sha1(z.read(n)), "digest mismatch " + n
        assert ("Name: %s\nSHA1-Digest: %s\n" % (n, _b64sha1(listed[n][1]))) in text.replace("\n ", ""), \
            "SF digest mismatch " + n

    # PKCS#7: certificates and RSA signature over the .SF
    certs = pkcs7.load_der_pkcs7_certificates(blob)
    assert any(c == leaf for c in certs) and any(c == root for c in certs), "chain not embedded"
    for c, issuer in ((leaf, root), (root, root)):
        assert c.issuer == issuer.subject, "issuer mismatch"
        issuer.public_key().verify(c.signature, c.tbs_certificate_bytes, padding.PKCS1v15(), hashes.SHA1())
    sig = _pkcs7_signature(blob)
    leaf.public_key().verify(sig, sf, padding.PKCS1v15(), hashes.SHA1())
    print("verify: OK - %d entries, digests match, SHA1withRSA signature valid, chain -> %s"
          % (len(listed), root.subject.rfc4514_string()))


def _pkcs7_signature(blob):
    """Pull the encryptedDigest out of the (single) SignerInfo with a tiny DER walker."""
    def tlv(b, p):
        tag = b[p]; p += 1
        ln = b[p]; p += 1
        if ln & 0x80:
            n = ln & 0x7f
            ln = int.from_bytes(b[p:p + n], "big"); p += n
        return tag, p, p + ln

    def children(b, start, end):
        out, p = [], start
        while p < end:
            t, s, e = tlv(b, p)
            out.append((t, s, e))
            p = e
        return out

    _, s, e = tlv(blob, 0)                         # ContentInfo
    ci = children(blob, s, e)
    _, s, e = tlv(blob, ci[1][1])                  # [0] -> SignedData
    sd = children(blob, s, e)
    signer_infos = sd[-1]                          # SET OF SignerInfo
    si = children(blob, signer_infos[1], signer_infos[2])[0]
    fields = children(blob, si[1], si[2])
    octet = [f for f in fields if f[0] == 0x04][-1]  # encryptedDigest OCTET STRING
    assert not any(f[0] == 0xA0 for f in fields), "signed attributes present"
    return blob[octet[1]:octet[2]]


def signbumf(keydir, manifest, out):
    key = serialization.load_pem_private_key(open(os.path.join(keydir, "bu.key.pem"), "rb").read(), None)
    cert = x509.load_der_x509_certificate(open(os.path.join(keydir, "bu.discroot.crt"), "rb").read())
    data = open(manifest, "rb").read()
    sig = key.sign(data, padding.PKCS1v15(), hashes.SHA1())
    blob = _tlv(0x03, b"\x00" + sig)
    open(out, "wb").write(blob)
    cert.public_key().verify(sig, data, padding.PKCS1v15(), hashes.SHA1())
    assert blob[0] == 0x03 and blob[-len(sig):] == sig
    print("signbumf: %s -> %s (%d-byte BIT STRING, verified with bu.discroot.crt)" % (manifest, out, len(blob)))


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "genkeys" and len(sys.argv) == 4:
        genkeys(sys.argv[2], sys.argv[3])
    elif cmd == "sign" and len(sys.argv) == 5:
        sign(sys.argv[2], sys.argv[3], sys.argv[4])
    elif cmd == "signbumf" and len(sys.argv) == 5:
        signbumf(sys.argv[2], sys.argv[3], sys.argv[4])
    elif cmd == "verify" and len(sys.argv) == 4:
        verify(sys.argv[2], sys.argv[3])
    else:
        print(__doc__)
        sys.exit(1)
