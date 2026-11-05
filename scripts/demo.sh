#!/usr/bin/env bash
# Demo: runs the full scenario from README.md against a running app (H2 + seed data).
# Needs curl and jq. Start the app first in another terminal: ./mvnw spring-boot:run
# Usage: scripts/demo.sh        (BASE sets the address, default http://localhost:8080)
set -euo pipefail

BASE=${BASE:-http://localhost:8080}
RUN_ID=$(date +%s)
SEED_APPROVED=00000000-0000-0000-0000-000000000001 # MA-1001, APPROVED (rm-anna, rev-sara)
SEED_PENDING=00000000-0000-0000-0000-000000000002  # MA-1002, PENDING (rm-anna)

PASSED=0
FAILED=0
ID=""
NEW_ID=""
TMP_FILES=""
BODY_FILE=""
HDR_FILE=""
STATUS=""
trap 'rm -f $TMP_FILES' EXIT

command -v curl >/dev/null || { echo "curl is required" >&2; exit 1; }
command -v jq >/dev/null || { echo "jq is required" >&2; exit 1; }

health=$(curl -s "$BASE/actuator/health" || true)
if [ "$(printf '%s' "$health" | jq -r '.status' 2>/dev/null || true)" != "UP" ]; then
  echo "The app is not UP at $BASE (no UP from /actuator/health)." >&2
  echo "Start it first with: ./mvnw spring-boot:run" >&2
  exit 1
fi
echo "App is UP at $BASE"

# request METHOD PATH USER [curl options...]. An empty USER sends no X-User-Id header.
# Sets STATUS, BODY_FILE and HDR_FILE for the helpers below.
request() {
  local method=$1 path=$2 user=$3
  shift 3
  BODY_FILE=$(mktemp)
  HDR_FILE=$(mktemp)
  TMP_FILES="$TMP_FILES $BODY_FILE $HDR_FILE"
  if [ -n "$user" ]; then
    STATUS=$(curl -s -o "$BODY_FILE" -D "$HDR_FILE" -w '%{http_code}' -X "$method" "$BASE$path" \
      -H "X-User-Id: $user" "$@") || STATUS=000
  else
    STATUS=$(curl -s -o "$BODY_FILE" -D "$HDR_FILE" -w '%{http_code}' -X "$method" "$BASE$path" \
      "$@") || STATUS=000
  fi
}

# field JQ_EXPR: a value from the last response body, empty if missing.
field() {
  jq -r "$1 // \"\"" "$BODY_FILE" 2>/dev/null || true
}

# header NAME: a response header from the last response, empty if missing.
header() {
  grep -i "^$1:" "$HDR_FILE" | tail -n 1 | tr -d '\r' | sed 's/^[^:]*: *//' || true
}

# expect HTTP_STATUS [ERROR_CODE]: compares the last response with what the step should get.
expect() {
  local want_code=${2:-} got_code
  got_code=$(field '.code')
  if [ "$STATUS" = "$1" ] && { [ -z "$want_code" ] || [ "$got_code" = "$want_code" ]; }; then
    PASSED=$((PASSED + 1))
    echo "   HTTP $STATUS${got_code:+ $got_code} as expected"
  else
    FAILED=$((FAILED + 1))
    echo "   HTTP $STATUS${got_code:+ $got_code}, expected $1${want_code:+ $want_code} -- FAILED"
  fi
}

step() {
  echo
  echo "== $*"
}

step "1. rm-erik creates a request on MA-1003 (POST /api/pricing-exceptions)"
request POST /api/pricing-exceptions rm-erik -H 'Content-Type: application/json' \
  -d '{"applicationId":"MA-1003","discountBps":40,"reason":"Long-standing customer, competing offer from another bank"}'
if [ "$STATUS" = 409 ] && [ "$(field '.code')" = ACTIVE_REQUEST_EXISTS ]; then
  expect 409 ACTIVE_REQUEST_EXISTS
  echo "   MA-1003 already has an active request, so this database ran the demo before."
  echo "   Steps 2-5 need a new request and are skipped. Restart the app for a full run."
else
  expect 201
  if [ "$STATUS" = 201 ]; then
    ID=$(field '.id')
    echo "   id $ID, status $(field '.status'), version $(field '.version')"
    echo "   Location $(header Location)"
  fi
fi

if [ -n "$ID" ]; then
  step "2. rm-erik reads the request (GET /api/pricing-exceptions/{id})"
  request GET "/api/pricing-exceptions/$ID" rm-erik
  expect 200
  ETAG=$(header ETag)
  echo "   status $(field '.status'), events $(field '[.events[].type] | join(" -> ")'), ETag $ETAG"

  step "3. rev-sara approves with If-Match set to the ETag (POST .../decision)"
  request POST "/api/pricing-exceptions/$ID/decision" rev-sara -H "If-Match: $ETAG" \
    -H 'Content-Type: application/json' -d '{"outcome":"APPROVED","comment":"Within my limit"}'
  expect 200
  echo "   status $(field '.status'), approvedDiscountBps $(field '.approvedDiscountBps'), decidedBy $(field '.decidedBy')"

  step "4. rm-erik reads it again: APPROVED, 2 events, decidedBy and decidedAt set"
  request GET "/api/pricing-exceptions/$ID" rm-erik
  expect 200
  echo "   status $(field '.status'), events $(field '[.events[].type] | join(" -> ")'), decidedAt $(field '.decidedAt'), ETag $(header ETag)"

  step "5. mortgage-system reads the approved discount for MA-1003"
  request GET /api/applications/MA-1003/approved-discount mortgage-system
  expect 200
  echo "   discountBps $(field '.discountBps'), requestId $(field '.requestId'), decidedBy $(field '.decidedBy')"
fi

step "6. rm-anna supersedes the seed PENDING request on MA-1002 (POST .../supersessions)"
echo "   6a. read the seed request (its ETag goes into If-Match)"
request GET "/api/pricing-exceptions/$SEED_PENDING" rm-anna
expect 200
STATE=$(field '.status')
if [ "$STATE" = PENDING ]; then
  OLD_ETAG=$(header ETag)
  SUPERSEDE_BODY='{"discountBps":60,"reason":"Competing offer from another bank, revised after discussion with the customer"}'
  echo "   6b. supersede it with an Idempotency-Key"
  request POST "/api/pricing-exceptions/$SEED_PENDING/supersessions" rm-anna \
    -H "If-Match: $OLD_ETAG" -H "Idempotency-Key: demo-$RUN_ID" -H 'Content-Type: application/json' \
    -d "$SUPERSEDE_BODY"
  expect 201
  NEW_ID=$(field '.id')
  echo "   new request $NEW_ID, status $(field '.status'), supersedesId $(field '.supersedesId'), ETag $(header ETag)"

  if [ -n "$NEW_ID" ]; then
    echo "   6c. retry the same call (same key, body and If-Match): replayed, not repeated"
    request POST "/api/pricing-exceptions/$SEED_PENDING/supersessions" rm-anna \
      -H "If-Match: $OLD_ETAG" -H "Idempotency-Key: demo-$RUN_ID" -H 'Content-Type: application/json' \
      -d "$SUPERSEDE_BODY"
    expect 201
    echo "   same id: $(field '.id'), Idempotent-Replayed: $(header Idempotent-Replayed)"

    echo "   6d. read the old request"
    request GET "/api/pricing-exceptions/$SEED_PENDING" rm-anna
    expect 200
    echo "   old request: status $(field '.status'), supersededById $(field '.supersededById')"

    echo "   6e. read the new request"
    request GET "/api/pricing-exceptions/$NEW_ID" rm-anna
    expect 200
    echo "   new request: status $(field '.status'), events $(field '[.events[].type] | join(" -> ")')"
  else
    echo "   skipped 6c-6e: the supersede did not return a new request id."
  fi
else
  echo "   skipped: $SEED_PENDING is $STATE, so an earlier run already superseded it."
fi

step "7. Error examples"
echo "   7a. no X-User-Id header"
request GET "/api/pricing-exceptions/$SEED_APPROVED" ""
expect 401 UNAUTHENTICATED

echo "   7b. second decision on an APPROVED request (seed MA-1001)"
request GET "/api/pricing-exceptions/$SEED_APPROVED" rm-anna
APPROVED_ETAG=$(header ETag)
request POST "/api/pricing-exceptions/$SEED_APPROVED/decision" rev-johan -H "If-Match: $APPROVED_ETAG" \
  -H 'Content-Type: application/json' -d '{"outcome":"DECLINED"}'
expect 409 INVALID_TRANSITION

echo "   7c. withdrawal without If-Match (seed MA-1002)"
request POST "/api/pricing-exceptions/$SEED_PENDING/withdrawal" rm-anna
expect 428 IF_MATCH_REQUIRED

echo "   7d. withdrawal with a stale If-Match (seed MA-1002)"
request POST "/api/pricing-exceptions/$SEED_PENDING/withdrawal" rm-anna -H 'If-Match: "99"'
expect 412 VERSION_MISMATCH

echo "   7e. a reviewer tries to create a request"
request POST /api/pricing-exceptions rev-sara -H 'Content-Type: application/json' \
  -d '{"applicationId":"MA-1003","discountBps":40,"reason":"Reviewer should not be able to do this"}'
expect 403 WRONG_ROLE

echo
echo "SUMMARY: $PASSED checks as expected, $FAILED unexpected. MA-1003 request: ${ID:-not created in this run, see step 1}. MA-1002 replacement: ${NEW_ID:-not created in this run, see step 6}."

if [ "$FAILED" -ne 0 ]; then
  exit 1
fi
