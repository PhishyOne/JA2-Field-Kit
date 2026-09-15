# Fixtures

This directory stores fixture metadata, not a public dump of JA2 saves.

- `provenance-manifest.json` is the public fixture registry.
- `provenance-manifest.schema.json` defines its machine-readable contract.
- `public/` is reserved for specifically reviewed, manifest-listed artifacts.
- `private/` is ignored and is the only staging location for locally supplied
  real saves and unapproved derived artifacts.

Read [`docs/fixture-policy.md`](../docs/fixture-policy.md) before adding or using
a fixture.

The supported validator runtime is CPython 3.12 on Linux x86-64. Create an
isolated environment and install the complete pinned closure before validation:

```bash
python3.12 -m venv .venv
.venv/bin/python -m pip install --require-hashes -r requirements/fixture-validation.lock
.venv/bin/python -m unittest discover -s tools/tests -v
.venv/bin/python tools/validate_fixture_manifest.py --head HEAD
```

`--head` is mandatory and repeatable. Each requested commit and every commit
reachable through all of its parents is inspected directly from Git objects.

On a trusted machine, require all cataloged local files to be present and match
their recorded size/digest with:

```bash
.venv/bin/python tools/validate_fixture_manifest.py --head HEAD --check-local-files
```

The known JA2 Reborn Build `04.12.02` entry is intentionally cataloged with a
pending identity. Its source save is not in the repository, and it cannot be
used by a test until its real size and SHA-256 are recorded and verified.
