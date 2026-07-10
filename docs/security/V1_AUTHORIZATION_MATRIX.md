# V1 Server Authorization Matrix

## Invariants

- Authentication establishes the actor; request usernames never do.
- A numeric resource ID is never an authorization credential.
- Durable SQLite membership/relationship rows are authoritative. In-memory
  online state controls delivery only.
- File authorization is checked before returning bytes, a local path, or a COS
  presigned URL.
- Denial logs contain operation categories and numeric identifiers, not message
  content, filenames, passwords, tokens, or supplied usernames.

## Operation Matrix

| Resource / operation | Required server-side relationship | V1 enforcement |
| --- | --- | --- |
| Register, login | Public request with credential verification | Authentication handler |
| Create room | Authenticated user | Session identity becomes creator/member/admin |
| Join/search room, get public room avatar | Authenticated user; room join may require room password | Join/search handlers |
| Leave room | Current room member | `room_members` check |
| Room list | Authenticated user | Query is scoped to the session user |
| Members, history, settings read | Current room member | `room_members` check |
| Send room message, inline file, large-file start | Current room member | Checked before persistence/quota/file creation |
| Large upload chunk/end/cancel | Upload owner; end also rechecks current room/friend relationship | Server-side `UploadState.userId` |
| Room recall | Current member, owned message, message belongs to supplied room | Membership plus message-room/owner checks |
| Mark room read | Current room member | Membership check before pointer update |
| Room administration | Current member and current room admin | Admin query joins `room_members` |
| Grant administrator | Acting room admin; target is current member | Handler and database checks |
| Change room limits | Acting room admin plus operator developer key | Both checks required |
| Direct message/history/file start | Current friendship participant | Friendship resolved from session user and peer |
| Mark direct chat read | Current friendship participant | Friendship participant check |
| Accept friend request | Authenticated request recipient | Request sender derived from pending DB row |
| Recall direct message | Authenticated owner; peer derived from message friendship | DB-derived notification recipient |
| TCP/HTTP/COS room file download | Current member of file's room | File-to-membership SQL join |
| TCP/HTTP/COS friend file download | Current friendship participant | File-to-friendship SQL join |

## Intentional Public-or-Discoverable Metadata

Authenticated user search, room search, user avatars, and room avatars remain
discoverable because V1 uses them before a friendship or room membership is
created. This is a product privacy choice, not proof of permission for history,
membership lists, messages, settings, or files. V2 must define profile and room
visibility preferences explicitly.

## Regression

Run the positive compatibility and negative authorization suites together:

```bash
python3 tools/verify_m0.py --v1-smoke
```

Any new handler that accepts a room, friendship, message, file, request, or
upload identifier must add both an allowed and a denied case before merge.
