"""Ed25519 signature verification, matching the Kotlin implementation.

The server verifies; it does not normally sign. Site signing keys live on supervisor devices, not
here, which is what makes the platform work through months of zero connectivity. ``sign`` exists
for tests and for the seed script, and is never reachable from an HTTP route.
"""

from __future__ import annotations

import hmac

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import (
    Ed25519PrivateKey,
    Ed25519PublicKey,
)

PRIVATE_KEY_SIZE = 32
PUBLIC_KEY_SIZE = 32
SIGNATURE_SIZE = 64


def verify(public_key: bytes, message: bytes, signature: bytes) -> bool:
    """Verify an Ed25519 signature.

    Never raises. Wrong key sizes, malformed key encodings and bad signatures all return
    ``False``, because callers must branch on trust rather than on exception handling.
    """
    if len(public_key) != PUBLIC_KEY_SIZE or len(signature) != SIGNATURE_SIZE:
        return False
    try:
        Ed25519PublicKey.from_public_bytes(public_key).verify(signature, message)
        return True
    except (InvalidSignature, ValueError):
        return False


def sign(private_key: bytes, message: bytes) -> bytes:
    """Sign with a raw 32-byte Ed25519 private key. Test and seed use only."""
    if len(private_key) != PRIVATE_KEY_SIZE:
        raise ValueError(
            f"Ed25519 private key must be {PRIVATE_KEY_SIZE} bytes, got {len(private_key)}"
        )
    return Ed25519PrivateKey.from_private_bytes(private_key).sign(message)


def public_key_from_private(private_key: bytes) -> bytes:
    if len(private_key) != PRIVATE_KEY_SIZE:
        raise ValueError(
            f"Ed25519 private key must be {PRIVATE_KEY_SIZE} bytes, got {len(private_key)}"
        )
    return (
        Ed25519PrivateKey.from_private_bytes(private_key)
        .public_key()
        .public_bytes_raw()
    )


def constant_time_equals(a: bytes, b: bytes) -> bool:
    """Length-independent comparison for chain hashes and signatures.

    These comparisons run on attacker-supplied input, so the server must not leak a matching
    prefix through response timing.
    """
    return hmac.compare_digest(a, b)
