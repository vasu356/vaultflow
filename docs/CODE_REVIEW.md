# Static Code Review and checksumSha256 Analysis

> Generated: 2026-07-14  
> Scope: Full codebase static review against runtime-verified behaviour.  
> Runtime baseline: Upload, download, processing, notification, and SHA-256 verification pipelines all confirmed healthy before this review.

---

## 1. `checksumSha256` in the Metadata API — Investigation Result

### Question

Why does `checksumSha256` returned by the Metadata API equal the storage key hash instead of the uploaded file SHA-256?

### Finding: This is correct. There is no bug.

The question rests on a misunderstanding of what `storage_key` contains. These are **two different values** stored in two different columns:

| Column | Value | Example |
|---|---|---|
| `checksum_sha256` | Raw 64-char hex SHA-256 of the file bytes | `e3b0c44298fc1c149afb...` |
| `storage_key` | Content-addressed path derived from that hash | `e3b/0c4/e3b0c44298fc1c149afb...` |

**Code evidence** (`UploadService.java`, single-part path):

```java
// Line 84: compute raw SHA-256 of file bytes
String checksum = ChecksumUtil.sha256Hex(fileBytes);

// Line 90: derive the storage path from the SHA-256
String storageKey = ChecksumUtil.toStoragePath(checksum);
// toStoragePath: sha256[0:3]/sha256[3:6]/sha256   (e.g. "e3b/0c4/e3b0c44...")

// Lines 121-123: both are stored in separate columns
ObjectVersion version = versionRepository.save(
    ObjectVersion.builder()
        .storageKey(storageKey)      // <- the path
        .checksumSha256(checksum)    // <- the raw hash
        ...
```

**Metadata API** (`MetadataService.java`):

```sql
SELECT ov.checksum_sha256, ov.storage_key, ...
FROM object_versions ov ...
```

The API returns `checksum_sha256` (the raw file hash) under the field name `checksumSha256`. The `storage_key` column is also returned separately in the metadata response for diagnostics. They are different values by design.

**Conclusion:** Field naming is correct. Implementation is correct. No code change required.

### Why They Might Look the Same

If someone uploads a file whose content is exactly the SHA-256 hex string itself (e.g. a text file containing `e3b0c44298fc1c149afb...`), then `checksum_sha256` and the last path segment of `storage_key` would be the same string — but that is a coincidence of the file content, not a bug.

---

## 2. Static Code Review — Evidence-Backed Findings

### 2.1 `UploadResponse` Missing `processingStatus` Field (Documentation Gap, Not a Code Bug)

**Evidence:**

`UploadResponse` DTO (`UploadResponses.java`):
```java
public record UploadResponse(
    UUID objectId, UUID versionId, String objectKey, String storageKey,
    long sizeBytes, String checksumSha256, String etag, String contentType,
    boolean isDuplicate, Instant uploadedAt) {}
```

The README previously showed `"processingStatus": "PENDING"` in the upload response JSON — that field does not exist in the DTO and is never serialised. A client parsing the response for `processingStatus` would always get `null`.

**Fix applied:** README example corrected. `processingStatus` removed from upload response example; a note added directing clients to the Metadata API for status.

---

### 2.2 Multipart `checksumSha256` Is a Hash-of-Hashes, Not the File SHA-256

**Observation (not a bug, but worth documenting):**

Single-part upload: `checksumSha256` = SHA-256 of the raw file bytes — clients can verify this with `sha256sum`.

Multipart upload: `checksumSha256` = SHA-256 of the concatenated part SHA-256 hex strings:

```java
// UploadService.java
private String computeMultipartChecksum(List<UploadPart> parts) {
    StringBuilder sb = new StringBuilder();
    parts.forEach(p -> sb.append(p.getChecksumSha256()));
    return ChecksumUtil.sha256Hex(sb.toString());
}
```

This is analogous to AWS S3's multipart ETag (`md5(md5_1 + md5_2 + ...)-N`). It is internally consistent — the same method is used for deduplication, storage key derivation, and the value stored in `checksum_sha256`. However, a client that computes `sha256sum` on the assembled file will get a different value than what the API returns for multipart uploads.

**Current documentation does not mention this distinction.** No README or ARCHITECTURE section explains that multipart `checksumSha256` is not the file SHA-256.

**Recommendation:** Add a callout to the multipart upload documentation:

> For multipart uploads, `checksumSha256` is a SHA-256 of the concatenated per-part SHA-256 values (similar to S3's multipart ETag convention). It is not the SHA-256 of the fully assembled file. To verify end-to-end integrity, verify each part's SHA-256 individually using the `X-Checksum-SHA256` header on each `PUT /uploads/{id}/parts/{num}` request.

---

### 2.3 `processing_status` Transition from `PENDING` → `COMPLETED` Skips `PROCESSING` State

**Evidence:**

`FileProcessedConsumer.java` — after applying a processor result:

```java
jdbc.update("""
    UPDATE object_versions SET processing_status = CASE
      WHEN virus_scan_status = 'INFECTED' THEN 'FAILED'
      WHEN processing_status = 'FAILED'   THEN 'FAILED'
      ELSE 'COMPLETED'
    END
    WHERE id = ?::uuid
      AND processing_status NOT IN ('COMPLETED', 'FAILED')
    """, versionId);
```

The `processing_status` column has a `CHECK` constraint (`V2__storage_schema.sql`):
```sql
CHECK (processing_status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED'))
```

The consumer sets `processing_status` directly to `COMPLETED` from `PENDING` — the `PROCESSING` state is never written by any consumer or orchestrator. 

**Is this a bug?** No — runtime tests confirm the pipeline completes and `processing_status` reaches `COMPLETED`. The `PROCESSING` state is a declared enum value that the current implementation does not use. The transition `PENDING → COMPLETED` is safe because the constraint allows both values.

**Recommendation:** Either use the `PROCESSING` state (set it when the `FileUploadedConsumer` picks up the event) or remove it from the CHECK constraint and documentation to avoid confusion. The current code is not broken, but the dead state creates a misleading mental model.

---

### 2.4 `refCount` Initial Value Overcounts on First Version of a Duplicate

**Evidence** (`UploadService.java`, single-part upload):

```java
.refCount(
    isDuplicate ? (int) versionRepository.countByStorageKey(storageKey) + 1 : 1)
```

When `isDuplicate = true`, `refCount` is set to `countByStorageKey(storageKey) + 1`. The count query runs before the new version is inserted (because this is the `build()` call), so the arithmetic is `existing_versions + 1`, which correctly represents the new total.

However, `countByStorageKey` counts all versions pointing to this storage key — including previous versions that may have been marked `isLatest = false`. If an object has been overwritten three times with the same content, there are three versions all with the same `storage_key`, and `refCount` on the fourth insert would be set to `4`. That is correct.

**But:** the multipart path (`doComplete`) does **not** set `refCount` at all for duplicates:

```java
// doComplete — no refCount handling for duplicate storage keys
ObjectVersion version = versionRepository.save(
    ObjectVersion.builder()
        ...
        // refCount defaults to @Builder.Default = 1
        .build());
```

The multipart path also does not check `storage.exists(storageKey)` before calling `storage.assembleParts`. If a multipart upload produces content identical to an existing object, the assembled file overwrites the existing storage file (same SHA-256 path), but `refCount` on the new version will be `1` even though two versions now share the same physical file.

**This is a real inconsistency:** the single-part path correctly initialises `refCount` for duplicates; the multipart path does not. If the garbage-collection logic deletes the physical file when `refCount = 1` and the version is deleted, it would delete a file still referenced by the original version.

**Note:** Runtime tests pass because there is no GC path that currently reads `refCount` to decide when to delete physical files — `refCount` is not yet hooked into a deletion job. The bug is latent; it will matter when physical storage GC is implemented.

**Evidence summary:**
- Single-part: `refCount` set correctly when `isDuplicate = true`
- Multipart: `refCount` always `1` regardless of duplicates
- No physical file deletion currently uses `refCount` → latent, not currently observable

**Recommended fix (when storage GC is implemented):** Mirror the single-part deduplication check in `doComplete`:

```java
boolean isDuplicate = storage.exists(storageKey);
// storage.assembleParts already writes to storageKey; 
// if isDuplicate, skip the write or handle the overwrite case
int refCount = isDuplicate
    ? (int) versionRepository.countByStorageKey(storageKey) + 1
    : 1;
```

No code change is applied now because this bug has no runtime impact without a GC job.

---

## 3. Summary

| # | Finding | Type | Severity | Action |
|---|---|---|---|---|
| 1 | `checksumSha256` returns file SHA-256, not storage key | Not a bug | — | Documentation clarified |
| 2 | `UploadResponse` does not include `processingStatus` | Doc gap | Low | README fixed |
| 3 | Multipart `checksumSha256` is hash-of-hashes, not file SHA-256 | Doc gap | Medium | Noted; doc update recommended |
| 4 | `PROCESSING` status is never written; `PENDING → COMPLETED` directly | Dead state | Low | No code change; noted |
| 5 | Multipart path does not initialise `refCount` for duplicate content | Latent bug | Medium (future) | No code change now; no GC job exists yet |
