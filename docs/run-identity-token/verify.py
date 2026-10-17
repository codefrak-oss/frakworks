"""Reference verifier for a frakworks run identity token (docs/run-identity-token.md).

Standard library only: ES256 (ECDSA P-256, SHA-256) is checked in pure Python, so a server with no crypto package can
use it as it is. A server with one (PyJWT, jose) should use that instead, following the same steps.

    claims = verify(token, jwks, audience="http://my-server/mcp")           # offline: signature, iss, scope, aud, exp
    claims = verify(token, jwks, audience=..., introspect=engine_introspect)  # and live: the run has not ended
"""
import base64
import hashlib
import json
import time

ISSUER = "frakworks-engine"
TYPE = "frakworks-run+jwt"
SCOPE = "frakworks.run.attest"

# NIST P-256
P = 0xFFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF
A = P - 3
N = 0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551
G = (0x6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296,
     0x4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5)


class InvalidToken(Exception):
    pass


def _b64(s):
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


def _add(p, q):
    if p is None:
        return q
    if q is None:
        return p
    if p[0] == q[0] and (p[1] + q[1]) % P == 0:
        return None
    if p == q:
        m = (3 * p[0] * p[0] + A) * pow(2 * p[1], -1, P) % P
    else:
        m = (q[1] - p[1]) * pow(q[0] - p[0], -1, P) % P
    x = (m * m - p[0] - q[0]) % P
    return x, (m * (p[0] - x) - p[1]) % P


def _mul(k, p):
    r = None
    while k:
        if k & 1:
            r = _add(r, p)
        p = _add(p, p)
        k >>= 1
    return r


def _on_curve(q):
    return (q[1] * q[1] - (q[0] ** 3 + A * q[0] + 0x5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B)) % P == 0


def es256_verify(public, message, signature):
    """Whether `signature` (raw r||s, 64 bytes) signs `message` under `public` ((x, y) ints)."""
    if len(signature) != 64 or not _on_curve(public):
        return False
    r, s = int.from_bytes(signature[:32], "big"), int.from_bytes(signature[32:], "big")
    if not (0 < r < N and 0 < s < N):
        return False
    z = int.from_bytes(hashlib.sha256(message).digest(), "big")
    w = pow(s, -1, N)
    point = _add(_mul(z * w % N, G), _mul(r * w % N, public))
    return point is not None and point[0] % N == r


def verify(token, jwks, audience, now=None, introspect=None):
    """The claims of `token`, checked against `jwks` (the engine's GET /.well-known/jwks.json) for `audience` (this
    server's own MCP URL, exactly as the engine has it); raises InvalidToken. With `introspect` (a function that POSTs
    {token, audience} to the engine's /run-tokens/introspect and returns the JSON), the run must also be live."""
    now = time.time() if now is None else now
    try:
        h64, c64, s64 = token.split(".")
        header, claims = json.loads(_b64(h64)), json.loads(_b64(c64))
    except Exception:
        raise InvalidToken("malformed")
    if header.get("alg") != "ES256" or header.get("typ") != TYPE:
        raise InvalidToken("not a run token")
    key = next((k for k in jwks.get("keys", []) if k.get("kid") == header.get("kid")), None)
    if key is None or key.get("kty") != "EC" or key.get("crv") != "P-256":
        raise InvalidToken("unknown kid")
    public = (int.from_bytes(_b64(key["x"]), "big"), int.from_bytes(_b64(key["y"]), "big"))
    if not es256_verify(public, f"{h64}.{c64}".encode(), _b64(s64)):
        raise InvalidToken("bad signature")
    if claims.get("iss") != ISSUER or claims.get("scope") != SCOPE:
        raise InvalidToken("wrong issuer or scope")
    if claims.get("sub") != f"run:{claims.get('jti')}":
        raise InvalidToken("sub and jti disagree")
    if claims.get("aud") != audience:
        raise InvalidToken("wrong audience")
    if not now < claims.get("exp", 0):
        raise InvalidToken("expired")
    if introspect is not None and not introspect(token, audience).get("active"):
        raise InvalidToken("run ended or revoked")
    return claims
