# Compatibility report v0.1

The Android app can prepare a compatibility report only after a complete,
bounded import reaches a structured `inspectV01` failure. Source-access errors,
oversized streams rejected before import, successful inspections, and the
initial/loading screens do not expose the action. Save contribution is a
separate future feature; this report never attaches or exports a save.

The normative JSON shape is
[`compatibility-report-v0.1.schema.json`](compatibility-report-v0.1.schema.json).
Serialization is deterministic: the implementation emits fields in the order
shown by the schema (`schema_version`, `app_version`, `source`, `format`, then
`failure`), uses two-space indentation and LF line endings, and ends with one
LF. Consumers must use `schema_version` rather than assuming later report
versions have the same fields.

## Privacy boundary

The report is built only from retained, non-sensitive import provenance and
the bounded format/failure presentation produced from `inspectV01`:

- the truthful app version compiled into the APK;
- coarse entry category, authoritative actual byte count, and lowercase
  SHA-256 already calculated for the complete imported snapshot (the schema
  enforces the importer's 16 MiB maximum);
- numeric save version when established, the allow-listed `04.12.02` build fact,
  and enumerated layout, compatibility, and producer facts;
- enumerated failure kind and diagnostic.

The report does not contain save bytes or a byte prefix; filename; provider
size or timestamp; raw content URI, filesystem path, provider/account/device
identifier; campaign fields or free-form gameplay text; merc names, nicknames,
or stats; stack traces or exception messages; offsets; or encryption rotation,
index, key, or digest details. Before the explicit Preview action, failure state
contains only allow-listed provenance, format, and failure facts and no
serialized report text. After that action, it also contains one immutable
preview text used by every output action. The source URI is consumed for the
immediate import and is neither reread nor retained to produce a report.

## Preview, copy, and share

The failure screen initially offers **Compatibility report** without generating
or serializing JSON. Selecting it is the explicit user action that generates
and reveals the exact JSON text and only then reveals
**Copy report** and **Share report**. Both actions consume that same immutable
preview text. Copy writes to the clipboard only on the Copy button click.
Share opens Android's chooser with an `ACTION_SEND` `text/plain` intent whose
only payload is `EXTRA_TEXT`; it has no URI, `ClipData`, or URI grant flags.

There is no submission endpoint, automatic background work, analytics,
network dependency, or `INTERNET` permission.
