"""Module docstring."""
import os
from typing import Optional

# A shape.
@dataclass(frozen=True)
class Shape:
    """A shape."""

    name: str = "x:y"

    def area(self) -> float:
        def helper():
            return 0
        return helper()

    @property
    def label(self):
        return f"{self.name}: {self.area()}"

    class Meta:
        ordering = ["name"]


if os.environ.get("FAST"):
    def speed():
        return 1
else:
    def speed():
        return 0


async def fetch(url: str,
                timeout: Optional[int] = None):
    async with session() as s:
        return await s.get(url)


def one_liner(x): return x * 2


class Empty: pass
