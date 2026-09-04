# Fixtures

This directory stores fixture metadata, not a public dump of JA2 saves.

- `provenance-manifest.json` is the public fixture registry.
- `provenance-manifest.schema.json` defines its machine-readable contract.
- `public/` is reserved for specifically reviewed, manifest-listed artifacts.
- `private/` is ignored and is the only staging location for locally supplied
  real saves and unapproved derived artifacts.

Read [`docs/fixture-policy.md`](../docs/fixture-policy.md) before adding or using
a fixture.

Validate the public registry with:

```bash
python3 tools/validate_fixture_manifest.py
```

On a trusted machine, require all cataloged local files to be present and match
their recorded size/digest with:

```bash
python3 tools/validate_fixture_manifest.py --check-local-files
```

The known JA2 Reborn Build `04.12.02` entry is intentionally cataloged with a
pending identity. Its source save is not in the repository, and it cannot be
used by a test until its real size and SHA-256 are recorded and verified.
