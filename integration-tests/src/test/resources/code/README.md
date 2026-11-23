# Code fixtures

- `core-snapshot/`: a copy of this repository's `core/src/main/kotlin` at commit 72a4f4c
  (2026-11-23), indexed by the code eval set (`../eval/core-code.jsonl`, SPEC §18.6). It is frozen on purpose:
  the questions point at its functions, so it must not follow the live code. These files are test resources,
  not compiled.
- The mini-project for the structure test, the `rag structure` golden and the function-name search lives in `cli/src/test/resources/code/mini-project/`.
