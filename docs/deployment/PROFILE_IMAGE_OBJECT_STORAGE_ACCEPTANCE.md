# Profile Image Object-Storage Acceptance

Status: M3 pre-activation runbook. A successful probe is necessary but does not
authorize runtime composition, historical import, or production cleanup.

## Safety boundary

Use a dedicated non-production bucket or tenant whose loss is acceptable. Before
running the command, independently verify the selected identity, endpoint, and
bucket. Grant only PUT, checksum-enabled HEAD/GET, and DELETE beneath
`avatars/sha256/`. Add a short lifecycle rule for that prefix so an interrupted
probe cannot leave permanent content.

The probe creates one randomized valid 8×8 PNG, verifies create-only retry,
downloads and compares exact bytes, deletes it, and verifies absence. It never
prints the bucket, endpoint, object key, checksum, credentials, provider error,
or response body. Do not retain populated environment files or shell history in
the repository or an acceptance record.

## Run the guarded probe

Use the four inactive `CHATROOM_ATTACHMENT_S3_*` values documented in
`JAVA_GATEWAY_CONFIGURATION.md`; the current attachment and profile-image paths
share the reviewed private S3-compatible store configuration. Supply temporary
credentials through the provider default chain and set both probe-only values:

```text
CHATROOM_PROFILE_IMAGE_S3_PROBE_CONFIRM=CREATE_READ_AND_DELETE_TEST_OBJECT
CHATROOM_PROFILE_IMAGE_S3_PROBE_CREDENTIAL_PROVIDER=default-chain
```

From `Backend/`, run with JDK 21:

```bash
./gradlew --no-daemon :object-storage-s3:probeProfileImageStorage
```

The only successful application output is equivalent to:

```text
profile-image object-store capability probe: PASS put=true retry=true read=true cleanup=true
```

Failure is nonzero and still attempts exact DELETE plus a final absence read.
After every run, independently confirm through provider inventory/audit tooling
that the probe object is absent. On cleanup failure, revoke the temporary
credential and use a reviewed provider procedure; do not rerun blindly.

## Evidence required before activation

Retain a dated, non-secret record containing:

- provider/product, region, and a non-sensitive environment label;
- reviewed least-privilege identity and bucket-policy references;
- PASS output and independent no-object-remains confirmation;
- lifecycle-rule evidence for abandoned content-addressed objects;
- provider timeout/retry settings and the cleanup claim lease relationship;
- rollback owner and procedure for disabling avatar handlers and cleanup;
- a restart exercise proving PostgreSQL metadata plus private object reads after
  the Java process is rebuilt.

Do not mark the roadmap gate complete from unit tests or probe implementation.
This functional check is not latency, throughput, durability, or capacity
evidence.
