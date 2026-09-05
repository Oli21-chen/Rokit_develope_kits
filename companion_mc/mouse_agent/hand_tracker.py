"""MediaPipe Hand Landmarker for laptop-side inference."""

from __future__ import annotations

import io
import logging
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Optional, Sequence

import mediapipe as mp
import numpy as np
from mediapipe.tasks import python as mp_python
from mediapipe.tasks.python import vision
from PIL import Image

from pointer_mapper import Landmark, to_display_landmarks

LOGGER = logging.getLogger(__name__)

# Solution B: slightly lower confidence than A-only (0.78) for fewer re-detect jumps.
MIN_HAND_DETECTION_CONFIDENCE = 0.75
MIN_HAND_PRESENCE_CONFIDENCE = 0.75
MIN_TRACKING_CONFIDENCE = 0.75


@dataclass
class HandTrackResult:
    hand_present: bool
    landmarks: list[Landmark]
    inference_ms: float


class HandTracker:
    def __init__(self, model_path: Path) -> None:
        if not model_path.is_file():
            raise FileNotFoundError(
                f"Hand landmarker model not found: {model_path}\n"
                "Download hand_landmarker.task into mouse_agent/models/"
            )
        base_options = mp_python.BaseOptions(model_asset_path=str(model_path))
        options = vision.HandLandmarkerOptions(
            base_options=base_options,
            running_mode=vision.RunningMode.VIDEO,
            num_hands=1,
            min_hand_detection_confidence=MIN_HAND_DETECTION_CONFIDENCE,
            min_hand_presence_confidence=MIN_HAND_PRESENCE_CONFIDENCE,
            min_tracking_confidence=MIN_TRACKING_CONFIDENCE,
        )
        self._landmarker = vision.HandLandmarker.create_from_options(options)
        LOGGER.info(
            "HandLandmarker confidences: detection=%.2f presence=%.2f tracking=%.2f",
            MIN_HAND_DETECTION_CONFIDENCE,
            MIN_HAND_PRESENCE_CONFIDENCE,
            MIN_TRACKING_CONFIDENCE,
        )
        self._timestamp_ms = 0

    def close(self) -> None:
        self._landmarker.close()

    def detect_jpeg(self, jpeg: bytes, timestamp_ms: Optional[int] = None) -> HandTrackResult:
        started = time.perf_counter()
        image = Image.open(io.BytesIO(jpeg)).convert("RGB")
        mp_image = mp.Image(image_format=mp.ImageFormat.SRGB, data=np.asarray(image))
        if timestamp_ms is None:
            self._timestamp_ms = max(self._timestamp_ms + 1, int(time.time() * 1000))
            ts = self._timestamp_ms
        else:
            ts = max(timestamp_ms, self._timestamp_ms + 1)
            self._timestamp_ms = ts
        result = self._landmarker.detect_for_video(mp_image, ts)
        inference_ms = (time.perf_counter() - started) * 1000.0
        first_hand: Sequence = result.hand_landmarks[0] if result.hand_landmarks else []
        raw = [(lm.x, lm.y, lm.z) for lm in first_hand]
        # JPEG is already rotated upright in FrameJpegEncoder (CameraX 270°).
        # Do NOT apply HandDisplayTransform extra 270° again — that swaps x/y motion.
        landmarks = to_display_landmarks(raw, extra_rotation_cw=0)
        return HandTrackResult(
            hand_present=bool(landmarks),
            landmarks=landmarks,
            inference_ms=inference_ms,
        )
