from __future__ import annotations

import random
import time
from dataclasses import dataclass


@dataclass(frozen=True)
class NetworkProfile:
    name: str
    delay_ms: float
    jitter_ms: float
    loss_pct: float


class ImpairmentEngine:
    """Application-layer network impairment simulator on sender side."""

    def __init__(self, profile: NetworkProfile, seed: int | None = None) -> None:
        self.profile = profile
        self._rng = random.Random(seed)

    def maybe_drop(self) -> bool:
        if self.profile.loss_pct <= 0:
            return False
        return self._rng.random() < (self.profile.loss_pct / 100.0)

    def one_way_delay_sec(self) -> float:
        base = self.profile.delay_ms
        jitter = self.profile.jitter_ms
        sampled_ms = base
        if jitter > 0:
            sampled_ms = base + self._rng.uniform(-jitter, jitter)
        if sampled_ms < 0:
            sampled_ms = 0
        return sampled_ms / 1000.0

    def apply_request_delay(self) -> float:
        sec = self.one_way_delay_sec()
        if sec > 0:
            time.sleep(sec)
        return sec

    def apply_response_delay(self) -> float:
        sec = self.one_way_delay_sec()
        if sec > 0:
            time.sleep(sec)
        return sec
