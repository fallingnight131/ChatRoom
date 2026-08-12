# Attachment Object-Storage Acceptance

Status: M3 pre-activation runbook. A successful probe is necessary but is not
sufficient to route product uploads or start cleanup.

## Safety boundary

Use a dedicated non-production bucket or tenant whose loss is acceptable. Never
point the probe at a production bucket. Before running it, verify outside this
repository which cloud identity and bucket the environment selects.

The temporary identity should be limited to the chosen bucket and the
`attachments/capability-probe-*` prefix, with only the operations needed for
presigned PUT, checksum HEAD/GET metadata, and DELETE. The bucket must also have
a short lifecycle rule for that prefix so an interrupted process or unavailable
provider eventually removes an object the command could not delete.

Configure CORS for the exact supported Web deployment origin. It must allow:

- method `PUT`;
- request headers `content-type`, `if-none-match`, and
  `x-amz-checksum-sha256` (plus any additional header named by the selected
  provider's signed request);
- the exact Web origin, or `*` only when the deployment deliberately uses
  non-credentialed presigned requests;
- the actual PUT response to include `Access-Control-Allow-Origin`.

Do not paste credentials, signed URLs, provider responses, shell history, or
populated environment files into an issue, commit, or acceptance record.

## Run the guarded probe

From `Backend/`, provide the four inactive storage values documented in
`JAVA_GATEWAY_CONFIGURATION.md`, temporary credentials through the provider's
standard default credential chain, and these three probe-only values:

```text
CHATROOM_ATTACHMENT_S3_PROBE_CONFIRM=CREATE_AND_DELETE_TEST_OBJECT
CHATROOM_ATTACHMENT_S3_PROBE_CREDENTIAL_PROVIDER=default-chain
CHATROOM_ATTACHMENT_S3_PROBE_WEB_ORIGIN=https://chat.example.test
```

Then run with the repository's JDK 21 and Gradle wrapper:

```bash
./gradlew --no-daemon :object-storage-s3:probeAttachmentStorage
```

The only successful application output is equivalent to:

```text
attachment object-store capability probe: PASS cors=true put=true replay=true checksum=true cleanup=true
```

Gradle may print ordinary build output. The application does not print the
bucket, endpoint, generated key, signed URL, credentials, response body, or
provider exception. A failure is nonzero and still attempts DELETE plus a final
absence check.

After the command, independently confirm from provider inventory/audit tooling
that no object remains in the probe prefix. If local output reports cleanup
failure, revoke the temporary credential and remove the object through the
provider console or reviewed CLI procedure; do not rerun blindly.

## Remaining acceptance before activation

The guarded command proves only the four capabilities named in its PASS line.
Before a later ADR can compose uploads or cleanup into `GatewayRuntime`, retain a
dated, non-secret acceptance record containing:

- provider/product and region, using a non-sensitive environment label rather
  than a bucket name;
- reviewed least-privilege identity and bucket-policy references;
- probe PASS output and independent no-object-remains confirmation;
- a separately reviewed unused-URL expiry test proving a short-lived signed PUT
  cannot create an object after expiry;
- lifecycle-rule evidence for abandoned probe/pending objects;
- the exact production Web-origin CORS policy review;
- rollback owner and procedure for disabling upload grants and cleanup.

Do not record a PASS in the roadmap until all items above are complete. Do not
interpret this functional probe as latency, throughput, durability, or capacity
evidence.
