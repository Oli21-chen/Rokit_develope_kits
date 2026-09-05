"""1€ filter for low-latency interactive smoothing (Casiez et al.)."""

from __future__ import annotations

import math


def _smoothing_factor(te: float, cutoff: float) -> float:
    r = 2.0 * math.pi * cutoff * te
    return r / (r + 1.0)


class OneEuroFilter:
    def __init__(
        self,
        *,
        min_cutoff: float = 1.2,
        beta: float = 0.04,
        d_cutoff: float = 1.0,
    ) -> None:
        self.min_cutoff = min_cutoff
        self.beta = beta
        self.d_cutoff = d_cutoff
        self._x_prev: float | None = None
        self._dx_prev = 0.0
        self._t_prev: float | None = None

    def reset(self) -> None:
        self._x_prev = None
        self._dx_prev = 0.0
        self._t_prev = None

    def filter(self, t: float, x: float) -> float:
        if self._x_prev is None or self._t_prev is None:
            self._x_prev = x
            self._t_prev = t
            self._dx_prev = 0.0
            return x

        te = t - self._t_prev
        if te <= 0.0:
            te = 1.0 / 30.0

        dx = (x - self._x_prev) / te
        alpha_d = _smoothing_factor(te, self.d_cutoff)
        dx_hat = alpha_d * dx + (1.0 - alpha_d) * self._dx_prev

        cutoff = self.min_cutoff + self.beta * abs(dx_hat)
        alpha = _smoothing_factor(te, cutoff)
        x_hat = alpha * x + (1.0 - alpha) * self._x_prev

        self._x_prev = x_hat
        self._dx_prev = dx_hat
        self._t_prev = t
        return x_hat


class OneEuroFilter2D:
    def __init__(
        self,
        *,
        min_cutoff: float = 1.2,
        beta: float = 0.04,
        d_cutoff: float = 1.0,
    ) -> None:
        self._x = OneEuroFilter(min_cutoff=min_cutoff, beta=beta, d_cutoff=d_cutoff)
        self._y = OneEuroFilter(min_cutoff=min_cutoff, beta=beta, d_cutoff=d_cutoff)

    def reset(self) -> None:
        self._x.reset()
        self._y.reset()

    def filter(self, t: float, x: float, y: float) -> tuple[float, float]:
        return self._x.filter(t, x), self._y.filter(t, y)
