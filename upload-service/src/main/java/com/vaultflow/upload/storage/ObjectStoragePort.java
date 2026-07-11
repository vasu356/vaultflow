package com.vaultflow.upload.storage;

import java.io.InputStream;
import java.util.List;

/**
 * Port (interface) for object storage operations.
 *
 * <p>Hexagonal architecture: the upload and download services depend on this interface, not any
 * specific storage implementation. This means we can swap LocalFileSystemStorage → S3Storage →
 * MinIOStorage without touching business logic.
 *
 * <p>Current implementation: {@link LocalFileSystemStorage} Future: S3CompatibleStorage using AWS
 * SDK v2 (async, non-blocking)
 *
 * <p>All methods are synchronous at this layer. The service layer wraps these in CompletableFuture
 * or virtual-thread-based async where needed.
 */
public interface ObjectStoragePort {

  /**
   * Store an object and return the storage key (content-addressed path). If an object with the same
   * key already exists (deduplication), the call is idempotent — returns the existing key without
   * writing again.
   *
   * @param storageKey content-addressed key (e.g. abc/def/abc123...)
   * @param data input stream of object data
   * @param sizeBytes expected size for pre-allocation and validation
   * @param contentType MIME type for metadata
   * @return the storage key where the object was stored
   */
  String store(String storageKey, InputStream data, long sizeBytes, String contentType);

  /**
   * Store a part of a multipart upload to a temporary location.
   *
   * @param sessionId upload session ID
   * @param partNumber 1-based part number
   * @param data chunk data stream
   * @param sizeBytes chunk size
   * @return temporary storage key for the part
   */
  String storePart(String sessionId, int partNumber, InputStream data, long sizeBytes);

  /**
   * Assemble all parts of a multipart upload into the final object. Streams parts sequentially to
   * avoid loading entire file into memory. Deletes temporary part files on success.
   *
   * @param storageKey final content-addressed key
   * @param sessionId upload session to assemble
   * @param partKeys ordered list of temporary part keys
   * @return bytes written
   */
  long assembleParts(String storageKey, String sessionId, List<String> partKeys);

  /**
   * Retrieve an object as a stream. Caller is responsible for closing the stream.
   *
   * @param storageKey storage key
   * @return input stream of object data
   */
  InputStream retrieve(String storageKey);

  /**
   * Retrieve a range of bytes from an object (HTTP Range request support).
   *
   * @param storageKey storage key
   * @param offset byte offset to start reading from
   * @param length number of bytes to read (-1 for remainder)
   * @return input stream of requested range
   */
  InputStream retrieveRange(String storageKey, long offset, long length);

  /** Delete an object. Called when ref_count reaches 0 (last reference removed). */
  void delete(String storageKey);

  /** Delete a temporary upload part. */
  void deletePart(String sessionId, int partNumber, String partKey);

  /** Check if a storage key exists without retrieving the object. Used for dedup fast-path. */
  boolean exists(String storageKey);

  /** Return the total size in bytes of a stored object. */
  long getSize(String storageKey);
}
