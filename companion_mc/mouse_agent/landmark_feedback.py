"""RKLM landmark feedback packet (laptop → glasses HUD overlay)."""

from __future__ import annotations

import hmac
import hashlib
import struct
from dataclasses import dataclass
from typing import Optional, Sequence

from pointer_mapper import Landmark

MAGIC = 0x524B4C4D  # RKLM
VERSION = 1
HEADER_SIZE = 16
LANDMARK_COUNT = 21
AUTH_TAG_SIZE = 8

FLAG_HAND_PRESENT = 1 << 0
FLAG_PRECISION_ACTIVE = 1 << 1

STRUCT_HEADER = struct.Struct("<IBBHII")
STRUCT_XY = struct.Struct("<ff")


def hmac_tag(token: str, body: bytes) -> bytes:
    digest = hmac.new(token.encode("utf-8"), body, hashlib.sha256).digest()
    return digest[:AUTH_TAG_SIZE]


@dataclass
class EncodedLandmarkFeedback:
    packet: bytes
    hand_present: bool
    precision_active: bool


def encode_landmark_feedback(
    *,
    token: str,
    session_id: int,
    sequence: int,
    t_ms: int,
    landmarks: Sequence[Landmark],
    hand_present: bool,
    precision_active: bool = False,
) -> EncodedLandmarkFeedback:
    flags = 0
    if hand_present:
        flags |= FLAG_HAND_PRESENT
    if precision_active:
        flags |= FLAG_PRECISION_ACTIVE
    header = STRUCT_HEADER.pack(
        MAGIC,
        VERSION,
        flags & 0xFF,
        session_id & 0xFFFF,
        sequence & 0xFFFFFFFF,
        t_ms & 0xFFFFFFFF,
    )
    xy_bytes = bytearray()
    if hand_present and len(landmarks) >= LANDMARK_COUNT:
        for index in range(LANDMARK_COUNT):
            lm = landmarks[index]
            xy_bytes.extend(STRUCT_XY.pack(lm.x, lm.y))
    else:
        xy_bytes.extend(b"\x00" * (LANDMARK_COUNT * STRUCT_XY.size))
    body = header + bytes(xy_bytes)
    packet = body + hmac_tag(token, body)
    return EncodedLandmarkFeedback(
        packet=packet,
        hand_present=hand_present,
        precision_active=precision_active,
    )


@dataclass
class DecodedLandmarkFeedback:
    session_id: int
    sequence: int
    t_ms: int
    hand_present: bool
    precision_active: bool
    landmarks: list[tuple[float, float]]
    auth_valid: bool


def decode_landmark_feedback(data: bytes, token: str) -> Optional[DecodedLandmarkFeedback]:
    expected_len = HEADER_SIZE + LANDMARK_COUNT * STRUCT_XY.size + AUTH_TAG_SIZE
    if len(data) < expected_len:
        return None
    body = data[: HEADER_SIZE + LANDMARK_COUNT * STRUCT_XY.size]
    tag = data[HEADER_SIZE + LANDMARK_COUNT * STRUCT_XY.size : expected_len]
    auth_valid = hmac.compare_digest(tag, hmac_tag(token, body))
    magic, version, flags, session_id, sequence, t_ms = STRUCT_HEADER.unpack(body[:HEADER_SIZE])
    if magic != MAGIC or version != VERSION:
        return None
    hand_present = bool(flags & FLAG_HAND_PRESENT)
    precision_active = bool(flags & FLAG_PRECISION_ACTIVE)
    landmarks: list[tuple[float, float]] = []
    offset = HEADER_SIZE
    for _ in range(LANDMARK_COUNT):
        x, y = STRUCT_XY.unpack_from(body, offset)
        landmarks.append((x, y))
        offset += STRUCT_XY.size
    return DecodedLandmarkFeedback(
        session_id=session_id & 0xFFFF,
        sequence=sequence & 0xFFFFFFFF,
        t_ms=t_ms & 0xFFFFFFFF,
        hand_present=hand_present,
        precision_active=precision_active,
        landmarks=landmarks,
        auth_valid=auth_valid,
    )
