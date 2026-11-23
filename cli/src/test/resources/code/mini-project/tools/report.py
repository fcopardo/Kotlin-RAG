"""Sales report from the order log."""
import csv


def load(path):
    """Read the order log: one row per order."""
    with open(path) as f:
        return list(csv.DictReader(f))


class Report:
    """Totals per product."""

    def __init__(self, rows):
        self.rows = rows

    def totals(self):
        out = {}
        for row in self.rows:
            out[row["sku"]] = out.get(row["sku"], 0) + int(row["cents"])

        return out
