"""RKFR chunked JPEG frame protocol (glasses → laptop)."""

from __future__ import annotations

import hmac
import hashlib
import struct
from dataclasses import dataclass
from typing import Optional

MAGIC = 0x524B4652  # RKFR
VERSION = 1
HEADER_SIZE = 28
AUTH_TAG_SIZE = 8
MAX_PAYLOAD = 1200

FLAG_OUTPUT_ENABLED = 1 << 0

STRUCT_HEADER = struct.Struct("<IBBHIIHHHHHH")


def hmac_tag(token: str, body: bytes) -> bytes:
    digest = hmac.new(token.encode("utf-8"), body, hashlib.sha256).digest()
    return digest[:AUTH_TAG_SIZE]


@dataclass
class FrameChunkMeta:
    session_id: int
    frame_seq: int
    t_ms: int
    rotation_deg: int
    width: int
    height: int
    chunk_index: int
    chunk_total: int
    output_enabled: bool


@dataclass
class DecodedFrameChunk:
    meta: FrameChunkMeta
    payload: bytes
    auth_valid: bool


@dataclass
class DecodedFrame:
    session_id: int
    frame_seq: int
    t_ms: int
    rotation_deg: int
    width: int
    height: int
    output_enabled: bool
    jpeg: bytes


def decode_chunk(data: bytes, token: str) -> Optional[DecodedFrameChunk]:
    if len(data) < HEADER_SIZE + AUTH_TAG_SIZE:
        return None
    payload_len = struct.unpack_from("<H", data, 26)[0]
    if len(data) < HEADER_SIZE + payload_len + AUTH_TAG_SIZE:
        return None
    body = data[: HEADER_SIZE + payload_len]
    tag = data[HEADER_SIZE + payload_len : HEADER_SIZE + payload_len + AUTH_TAG_SIZE]
    auth_valid = hmac.compare_digest(tag, hmac_tag(token, body))
    (
        magic,
        version,
        flags,
        session_id,
        frame_seq,
        t_ms,
        rotation_deg,
        width,
        height,
        chunk_index,
        chunk_total,
        parsed_len,
    ) = STRUCT_HEADER.unpack(body[:HEADER_SIZE])
    if magic != MAGIC or version != VERSION or parsed_len != payload_len:
        return None
    payload = body[HEADER_SIZE:]
    return DecodedFrameChunk(
        meta=FrameChunkMeta(
            session_id=session_id & 0xFFFF,
            frame_seq=frame_seq & 0xFFFFFFFF,
            t_ms=t_ms & 0xFFFFFFFF,
            rotation_deg=rotation_deg & 0xFFFF,
            width=width & 0xFFFF,
            height=height & 0xFFFF,
            chunk_index=chunk_index & 0xFFFF,
            chunk_total=chunk_total & 0xFFFF,
            output_enabled=bool(flags & FLAG_OUTPUT_ENABLED),
        ),
        payload=payload,
        auth_valid=auth_valid,
    )


class FrameReassembler:
    """Collect RKFR chunks into complete JPEG blobs."""

    def __init__(self) -> None:
        self._partial: dict[tuple[int, int], dict[int, bytes]] = {}
        self._totals: dict[tuple[int, int], int] = {}

    def reset(self) -> None:
        self._partial.clear()
        self._totals.clear()

    def ingest(self, chunk: DecodedFrameChunk) -> Optional[DecodedFrame]:
        meta = chunk.meta
        key = (meta.session_id, meta.frame_seq)
        if meta.chunk_total <= 0 or meta.chunk_index >= meta.chunk_total:
            return None
        parts = self._partial.setdefault(key, {})
        parts[meta.chunk_index] = chunk.payload
        self._totals[key] = meta.chunk_total
        if len(parts) < meta.chunk_total:
            return None
        ordered = b"".join(parts[i] for i in range(meta.chunk_total))
        self._partial.pop(key, None)
        self._totals.pop(key, None)
        return DecodedFrame(
            session_id=meta.session_id,
            frame_seq=meta.frame_seq,
            t_ms=meta.t_ms,
            rotation_deg=meta.rotation_deg,
            width=meta.width,
            height=meta.height,
            output_enabled=meta.output_enabled,
            jpeg=ordered,
        )
