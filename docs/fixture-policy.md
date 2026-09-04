# Fixture policy

## Purpose and boundary

Parser claims must be backed by identifiable evidence without turning this
public repository into a collection of personal saves or redistributed game
data. This policy governs fixture admission, provenance, expected results,
storage, hashing, and test use.

This tranche defines fixture handling only. It does not add a real-format
parser, header offsets, encryption logic, Android code, save editing, copied
JA2/JA2 Reborn implementation text, or a project license.

## Non-negotiable rules

1. Treat every real save as private and non-redistributable unless a specific
   review records a defensible basis for repository inclusion.
2. Never modify a supplied save in place. Work from a copy, open fixture input
   read-only, and write experiments to a separate path.
3. Every external fixture artifact and every vector claimed to represent a real
   format must have a manifest entry before it is used as evidence.
4. A fixture is test-eligible only when its exact byte size and lowercase
   SHA-256 digest are verified and its expected logical outputs are recorded.
5. Never commit, attach, cache, upload as an Actions artifact, or print the
   contents of a local-only save.
6. Do not derive test bytes by copying source implementation text, tables, or
   code-shaped logic into this repository.

Small byte arrays generated directly inside a unit test are exempt from the
manifest only when their construction and assertions are adjacent, they contain
no bytes copied from a real save, and the test does not claim that they are a
real-format fixture. The existing boundary/probe tests fit this narrow
exception.

## Fixture categories

### `synthetic`

Project-authored bytes made from a documented recipe. A synthetic vector must
not be a slice of a real save, a transcription of protected implementation
data, or a disguised derived fixture. Its manifest entry identifies the recipe
and the exact generated-byte digest.

A manifested generated fixture uses a side-effect-free Python script whose
standard output is exactly the fixture bytes. The validator executes that
tracked script and checks its output size and SHA-256 before admission. Small
inline vectors remain covered only by the narrow exemption above.

Synthetic vectors are the default for public CI: boundary lengths, malformed
inputs, sentinel values, and independently written structures once the relevant
format facts have been reviewed.

### `real-local`

An unchanged save supplied locally by its owner or another authorized tester.
It is staged only below `fixtures/private/`, which Git ignores. The public
manifest may catalog non-sensitive source/build facts and known logical results
without publishing the file.

A catalog entry may temporarily have `identity.status: "pending"` when the file
is not available during documentation work. That entry is evidence inventory,
not a usable test fixture. Before any local test consumes it, record its exact
size and SHA-256 digest and change the identity status to `verified` in an
appropriate manifest. Do not invent missing identity values.

### `derived-sanitized`

A minimized or sanitized artifact derived from a real save. Derivation does not
erase copyright, ownership, or privacy concerns. Replacing names or truncating a
file does not by itself make redistribution appropriate.

Its entry must pair each parent fixture ID with the verified SHA-256 from that
parent's entry in the same manifest, explain the byte-level derivation and
minimization goal, state what was sanitized, and cite the review that decided
whether the result may enter the repository. If that review is not clear, the
result remains local-only.

## Provenance manifest

`fixtures/provenance-manifest.json` is the public registry.
`fixtures/provenance-manifest.schema.json` is its schema. Each entry records:

- stable fixture ID, category, and description;
- source family, save version, product/build, platform context, origin, and
  evidence notes;
- storage mode and project-relative path or deterministic generator;
- identity state, byte size, and SHA-256;
- derivation lineage when applicable;
- redistribution status, basis, and review reference;
- expected success/failure and logical-output assertions;
- public-CI, local-only, or excluded handling; and
- the mandatory read-only input policy.

Expected assertions target the eventual serialized `SaveInspection` logical
model using JSON Pointers. They describe user-visible meaning rather than raw
offsets or decryption internals. `equals` compares an exact value;
`length-equals` compares an array or string length. Partial evidence must say
`completeness: "partial"` rather than filling unknown fields with guesses.

Do not put personal names, campaign names, custom merc names, device paths,
original filenames, credentials, or save contents in the public manifest.

## Redistribution status is not a license

The statuses mean:

- `approved-for-repository`: a recorded review permits this specific artifact
  to be stored in this repository.
- `not-permitted`: keep the artifact local.
- `pending-review`: do not publish until review is complete.

This is an operational admission gate, not a copyright determination or a grant
of reuse rights. The repository intentionally still has no license. Adding a
fixture does not settle Field Kit licensing, and the absence of a project
license must not be worked around by placing license claims in fixture metadata.

## Hashing and identity

SHA-256 covers the exact file bytes, with no decompression, newline conversion,
or preprocessing. Record the byte size from the same copy. A changed size or
digest is a different artifact and needs a new identity review; never silently
update a digest merely to make a test pass.

Typical local commands are:

```bash
wc -c < fixtures/private/example.sav
sha256sum fixtures/private/example.sav
```

On Android/Termux, the same commands are available from `coreutils`. Hash the
staged copy, not the only original save.

## Admission workflow

1. Copy the source into `fixtures/private/` as an ordinary file, not a symlink,
   without overwriting an existing file. Preserve the original elsewhere.
2. Classify it as synthetic, real-local, or derived-sanitized.
3. Record non-sensitive provenance and expected logical outputs.
4. Calculate size and SHA-256. Mark identity verified only after both match the
   staged bytes.
5. Decide redistribution separately. Default to `not-permitted` for a real save
   and `pending-review` for a derived artifact.
6. Run the manifest validator. Run private fixture tests locally only when such
   tests exist.
7. For proposed public bytes, review the artifact and provenance in a Draft PR;
   use an explicit force-add only after the manifest says
   `approved-for-repository`.

## CI handling

Hosted public CI validates the public manifest and uses only:

- deterministic synthetic vectors; and
- repository fixtures with verified identity and
  `approved-for-repository` status.

`ci.mode` is explicit per entry: `public` may run in hosted CI, `local-only`
requires a trusted local file, and `excluded` records a deliberate non-test
entry with its reason. Repository inclusion and CI participation are separate
decisions.

Hosted CI must not receive a local-only save through repository history,
secrets, caches, artifacts, release assets, network downloads, or encoded text.
The normal absence of a private fixture is not a CI skip or failure; public tests
must use synthetic or separately approved evidence for their required coverage.

Local-only tests are opt-in and must fail clearly when their requested fixture
is absent, pending identity verification, or hash-mismatched. They must never
fall back to scanning arbitrary user directories. A future secure private runner
requires a separate privacy, retention, logging, and authorization decision; it
is not created by this policy.

`tools/validate_fixture_manifest.py` enforces the public repository guardrails
without opening local saves in hosted CI. Pass `--check-local-files` only on a
trusted local machine when all local entries are present and verified.
