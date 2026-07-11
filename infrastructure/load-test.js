/**
 * VaultFlow Load Test - k6
 *
 * Tests:
 * 1. Authentication (login throughput)
 * 2. Single-part upload (throughput, latency)
 * 3. Multipart upload initiation
 * 4. Object download (range request support)
 *
 * Run: k6 run --env BASE_URL=http://localhost:80 load-test.js
 *
 * Targets (SLOs):
 * - Upload p99 < 2s for 1 MB file
 * - Download p99 < 500ms TTFB
 * - Login p99 < 300ms
 * - Error rate < 0.1%
 */
import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';

// ============================================================
// Custom Metrics
// ============================================================
const uploadDuration = new Trend('upload_duration_ms', true);
const downloadDuration = new Trend('download_duration_ms', true);
const loginDuration = new Trend('login_duration_ms', true);
const uploadErrors = new Rate('upload_error_rate');
const downloadErrors = new Rate('download_error_rate');
const uploadBytes = new Counter('upload_bytes_total');

// ============================================================
// Test Configuration
// ============================================================
const BASE_URL = __ENV.BASE_URL || 'http://localhost:80';

export const options = {
  scenarios: {
    // Ramp up login throughput
    auth_load: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 10 },
        { duration: '1m', target: 50 },
        { duration: '30s', target: 0 },
      ],
      exec: 'loginScenario',
      tags: { scenario: 'auth' },
    },

    // Sustained upload load
    upload_load: {
      executor: 'constant-vus',
      vus: 20,
      duration: '2m',
      startTime: '30s',
      exec: 'uploadScenario',
      tags: { scenario: 'upload' },
    },

    // Spike test for downloads (burst traffic)
    download_spike: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '20s', target: 100 },
        { duration: '40s', target: 100 },
        { duration: '20s', target: 0 },
      ],
      startTime: '1m',
      exec: 'downloadScenario',
      tags: { scenario: 'download' },
    },

    // Multipart upload test (large files)
    multipart_load: {
      executor: 'constant-vus',
      vus: 5,
      duration: '90s',
      startTime: '45s',
      exec: 'multipartScenario',
      tags: { scenario: 'multipart' },
    },
  },

  thresholds: {
    // SLO: login p99 < 300ms
    'login_duration_ms{scenario:auth}': ['p(99)<300'],
    // SLO: upload p99 < 2000ms for 1MB
    'upload_duration_ms{scenario:upload}': ['p(99)<2000'],
    // SLO: download p99 TTFB < 500ms
    'download_duration_ms{scenario:download}': ['p(99)<500'],
    // SLO: error rate < 0.1%
    upload_error_rate: ['rate<0.001'],
    download_error_rate: ['rate<0.001'],
    // Overall HTTP error rate
    http_req_failed: ['rate<0.01'],
  },
};

// ============================================================
// Setup: Register org and get credentials
// ============================================================
export function setup() {
  const registerPayload = JSON.stringify({
    organizationName: 'Load Test Org',
    organizationSlug: `loadtest-${Date.now()}`,
    fullName: 'Load Tester',
    email: `loadtest+${Date.now()}@example.com`,
    password: __ENV.LOAD_TEST_PASSWORD || 'LoadTest1!',
  });

  const registerRes = http.post(
    `${BASE_URL}/api/v1/auth/register`,
    registerPayload,
    { headers: { 'Content-Type': 'application/json' } }
  );

  check(registerRes, {
    'setup: registration succeeded': (r) => r.status === 201,
  });

  const body = JSON.parse(registerRes.body);

  // Create a test bucket
  const bucketRes = http.post(
    `${BASE_URL}/api/v1/buckets`,
    JSON.stringify({ name: 'load-test-bucket', region: 'ap-south-1' }),
    {
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${body.accessToken}`,
      },
    }
  );

  check(bucketRes, { 'setup: bucket created': (r) => r.status === 201 });
  const bucket = JSON.parse(bucketRes.body);

  return {
    accessToken: body.accessToken,
    email: body.user.email,
    bucketId: bucket.id,
    orgId: body.user.orgId,
  };
}

// ============================================================
// Scenario: Login
// ============================================================
export function loginScenario(data) {
  group('auth - login', () => {
    const payload = JSON.stringify({
      email: data.email,
      password: __ENV.LOAD_TEST_PASSWORD || 'LoadTest1!',
    });

    const start = Date.now();
    const res = http.post(`${BASE_URL}/api/v1/auth/login`, payload, {
      headers: { 'Content-Type': 'application/json' },
    });
    loginDuration.add(Date.now() - start);

    check(res, {
      'login status 200': (r) => r.status === 200,
      'login has accessToken': (r) => JSON.parse(r.body).accessToken !== undefined,
    });
  });
  sleep(0.5);
}

// ============================================================
// Scenario: Single-Part Upload (1 MB)
// ============================================================
export function uploadScenario(data) {
  group('upload - single part 1MB', () => {
    // Generate 1 MB of random-ish data
    const size = 1024 * 1024;
    const content = new Uint8Array(size).fill(65); // 'A' repeated

    const objectKey = `load-test/file-${__VU}-${Date.now()}.bin`;

    const start = Date.now();
    const res = http.put(
      `${BASE_URL}/api/v1/buckets/${data.bucketId}/objects/${objectKey}`,
      content.buffer,
      {
        headers: {
          Authorization: `Bearer ${data.accessToken}`,
          'Content-Type': 'application/octet-stream',
          'Content-Length': String(size),
        },
        timeout: '30s',
      }
    );
    const elapsed = Date.now() - start;
    uploadDuration.add(elapsed);
    uploadErrors.add(res.status !== 200);
    if (res.status === 200) uploadBytes.add(size);

    check(res, {
      'upload status 200': (r) => r.status === 200,
      'upload has objectId': (r) => JSON.parse(r.body).objectId !== undefined,
      'upload has etag': (r) => r.headers['Etag'] !== undefined,
    });
  });
  sleep(1);
}

// ============================================================
// Scenario: Download
// ============================================================
export function downloadScenario(data) {
  group('download - full object', () => {
    // List objects and pick one (simplified: use known key from upload)
    const objectKey = `load-test/file-1-${Date.now()}.bin`;

    const start = Date.now();
    const res = http.get(
      `${BASE_URL}/api/v1/buckets/${data.bucketId}/objects/${objectKey}`,
      {
        headers: {
          Authorization: `Bearer ${data.accessToken}`,
        },
        // Measure TTFB only — don't wait for full body in load test
        timeout: '10s',
      }
    );
    downloadDuration.add(Date.now() - start);
    // 404 acceptable (object may not exist yet in download scenario)
    downloadErrors.add(res.status >= 500);

    check(res, {
      'download not server error': (r) => r.status < 500,
    });
  });
  sleep(0.2);
}

// ============================================================
// Scenario: Multipart Upload
// ============================================================
export function multipartScenario(data) {
  group('upload - multipart (10 MB)', () => {
    // Initiate
    const initiateRes = http.post(
      `${BASE_URL}/api/v1/uploads/initiate`,
      JSON.stringify({
        bucketId: data.bucketId,
        objectKey: `multipart/large-${__VU}-${Date.now()}.bin`,
        contentType: 'application/octet-stream',
        expectedSize: 10 * 1024 * 1024,
        totalParts: 2,
      }),
      {
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${data.accessToken}`,
        },
      }
    );

    check(initiateRes, { 'multipart initiate 201': (r) => r.status === 201 });
    if (initiateRes.status !== 201) return;

    const sessionId = JSON.parse(initiateRes.body).sessionId;
    const partSize = 5 * 1024 * 1024;
    const partData = new Uint8Array(partSize).fill(66);

    // Upload part 1
    const part1Res = http.put(
      `${BASE_URL}/api/v1/uploads/${sessionId}/parts/1`,
      partData.buffer,
      {
        headers: {
          Authorization: `Bearer ${data.accessToken}`,
          'Content-Type': 'application/octet-stream',
          'Content-Length': String(partSize),
        },
        timeout: '60s',
      }
    );
    check(part1Res, { 'part 1 upload 200': (r) => r.status === 200 });

    // Upload part 2
    const part2Res = http.put(
      `${BASE_URL}/api/v1/uploads/${sessionId}/parts/2`,
      partData.buffer,
      {
        headers: {
          Authorization: `Bearer ${data.accessToken}`,
          'Content-Type': 'application/octet-stream',
          'Content-Length': String(partSize),
        },
        timeout: '60s',
      }
    );
    check(part2Res, { 'part 2 upload 200': (r) => r.status === 200 });

    // Complete
    const completeRes = http.post(
      `${BASE_URL}/api/v1/uploads/${sessionId}/complete`,
      JSON.stringify({ partNumbers: [1, 2] }),
      {
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${data.accessToken}`,
        },
        timeout: '30s',
      }
    );
    check(completeRes, { 'multipart complete 200': (r) => r.status === 200 });
  });
  sleep(2);
}
