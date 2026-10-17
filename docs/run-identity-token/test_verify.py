"""Tests for verify.py: python3 -m unittest docs/run-identity-token/test_verify.py (or run it from its directory).
The tokens are signed here with the same curve math, in the shape the engine's RunTokens.sign writes."""
import base64
import hashlib
import json
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import verify as v  # noqa: E402

AUD = "http://ops-server.ops.svc.cluster.local:8737/mcp"


def b64(b):
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


def keypair(d):
    return d, v._mul(d, v.G)


def jwks(public, kid):
    return {"keys": [{"kty": "EC", "crv": "P-256", "kid": kid, "use": "sig", "alg": "ES256",
                      "x": b64(public[0].to_bytes(32, "big")), "y": b64(public[1].to_bytes(32, "big"))}]}


def sign(d, kid, claims, k=0x1234567):
    h = b64(json.dumps({"alg": "ES256", "typ": v.TYPE, "kid": kid}).encode())
    c = b64(json.dumps(claims).encode())
    z = int.from_bytes(hashlib.sha256(f"{h}.{c}".encode()).digest(), "big")
    r = v._mul(k, v.G)[0] % v.N
    s = pow(k, -1, v.N) * (z + r * d) % v.N
    return f"{h}.{c}.{b64(r.to_bytes(32, 'big') + s.to_bytes(32, 'big'))}"


def claims(run="run-9", aud=AUD, exp=2000):
    return {"iss": v.ISSUER, "sub": f"run:{run}", "jti": run, "aud": aud, "repo": "example-org/example-repo",
            "issue": 37, "mode": "work", "scope": v.SCOPE, "iat": 1000, "exp": exp}


class VerifyTest(unittest.TestCase):
    d, pub = keypair(0xC0FFEE)
    keys = jwks(pub, "k1")

    def test_a_genuine_token_verifies(self):
        got = v.verify(sign(self.d, "k1", claims()), self.keys, AUD, now=1500)
        self.assertEqual(("run-9", "example-org/example-repo", 37), (got["jti"], got["repo"], got["issue"]))

    def test_forged_other_key_other_audience_and_expired_fail(self):
        token = sign(self.d, "k1", claims())
        h, c, s = token.split(".")
        forged = b64(json.dumps(claims(run="run-8")).encode())
        cases = {
            "bad signature": (f"{h}.{forged}.{s}", AUD, 1500),
            "wrong audience": (token, "https://other.example.com/mcp", 1500),
            "expired": (token, AUD, 2000),
        }
        for reason, (t, aud, now) in cases.items():
            with self.assertRaisesRegex(v.InvalidToken, reason):
                v.verify(t, self.keys, aud, now=now)
        d2, _ = keypair(0xBADBAD)
        with self.assertRaisesRegex(v.InvalidToken, "bad signature"):
            v.verify(sign(d2, "k1", claims()), self.keys, AUD, now=1500)

    def test_an_ended_run_fails_introspection(self):
        token = sign(self.d, "k1", claims())
        with self.assertRaisesRegex(v.InvalidToken, "ended"):
            v.verify(token, self.keys, AUD, now=1500, introspect=lambda t, a: {"active": False})
        self.assertEqual("run-9", v.verify(token, self.keys, AUD, now=1500, introspect=lambda t, a: {"active": True})["jti"])


if __name__ == "__main__":
    unittest.main()
