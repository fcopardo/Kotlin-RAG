# Snowball reference data

- `spanish.sbl`: the Snowball Spanish stemming algorithm (snowballstem/snowball, `algorithms/spanish.sbl`).
- `spanish-voc-output.tsv`: every 4th line of snowballstem/snowball-data `spanish/voc.txt` and `spanish/output.txt`,
  word and expected stem separated by a tab. `SpanishStemmerTest` checks the Kotlin port against it.

Both are © Dr Martin Porter and Richard Boulton, BSD-licensed: see `COPYING`.
