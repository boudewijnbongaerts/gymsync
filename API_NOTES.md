# API_NOTES — mywellness (Technogym) endpoints, verified October 2026

Captured live from a real user session in Chrome (endusernext.mywellness.com, web client 2.0.1-1021)
plus the web client's SDK source (vendor bundle). Every **[VERIFY]** from the build guide is resolved below.

## Common
- All calls `POST`, JSON body (`{}` when empty), `?_c=en-US` query.
- Headers: `Content-Type: application/json`, `X-MWAPPS-APPID: EC1D38D7-D359-48D0-A60C-D8C0B8FB9DF9`,
  `X-MWAPPS-CLIENT: enduserweb`, `X-MWAPPS-CLIENTVERSION: 2.0.1-1021,enduserweb`, `Authorization: Bearer <token>` (not on login).
- **Every authenticated response carries a fresh `token` + `expireIn: 3600`** (seconds). The SDK also reads a response
  `Authorization` header if present. Token is opaque-ish: base64 segment `yyyyMMddHHmmss|credentialId|appId|…` + HMAC-like hex.
- Errors: body `errors[]` with `{type, errorMessage, field, details}`. Token expiry = `type == "Security"` and
  (`field == "TokenNotValid"` or `details == "TokenNotValid"`) — **confirmed from SDK source**, not observed live.
  HTTP 401 without such a body is also treated as token-not-valid by the SDK. HTTP 403 = permission denied.
- A request for an unknown resource can return **HTTP 200 with no `data` key** (see session_detail_unknown.json). Treat missing `data` as an error.
- Observed latency: Me ~140 ms, ActivityHistory page 1 up to ~4 s, session detail 70–400 ms.
  **ActivityHistory with `pager` set (page 2) hung for >60 s / failed twice** after working once. Use a 60 s timeout and treat a
  failed page 2 as "partial run" (write what page 1 gave, report the error), not as a fatal error.

## Login — VERIFIED (live, 200)
`POST https://core.mywellness.com/v2/enduser/authentication/login` (no Authorization header)
Body: `{"username": "<email>", "password": "<pw>", "keepMeLoggedIn": true}` (SDK also sends `sessionId`, `mfaCode`, both null/omitted).
Success (200): `{"accountConfirmed": true, "userContext": {id, accountUsername, email, firstName, …}, "result": "Success",
"facilities": [{"id", "url", "name", …}], "token": "<373 chars>"}` — response header `authorization` is also set.
**No `refreshToken` for the enduser channel** (the SDK's refresh endpoint is `/v2/pro/…`, pro channel only).
Wrong password: **HTTP 401** `{"result": "UsernameOrPasswordNotValid"}`.
Other `result` values (from SDK enum): `MfaRequired`, `MfaLastAttempt`, `MfaSessionIdNotValid`, `UnknownUser`,
`SuccessButMustAcceptPolicy`, `ValidCredential`, plus `accountLockedInfo: {maxNumberOfAttempts, blockedFor}` on lockout.
The test account had `mfaEnabled: false`.
**Decision:** the token lives 1 h and there is no refresh → the app stores the password (encrypted) and logs in fresh on every sync.
No token persistence needed.

## Me — VERIFIED
`POST https://core.mywellness.com/application/{APPID}/Me?_c=en-US`, body `{}`.
User id at `data.id`. Also `data.olsonTimeZone` ("Europe/Berlin"), `data.mfaEnabled`. Fixture: `me.json` (PII redacted).
Not needed by the app: login already returns `userContext.id` and `facilities`.

## Facilities — VERIFIED (path corrected)
`POST https://services.mywellness.com/Core/User/{userId}/GetMyAccountFacilities?_c=en-US`, body `{}`.
(The guide's guess `/application/{APPID}/GetMyAccountFacilities` → 404.)
`data[]` has the real club (`url` = the facility slug used in service URLs) and `url: "tgmobileapp"` (`isDemo: true`, Technogym app pseudo-facility).
**Rule:** use the first facility with `isDemo == false`; it is also in the login response.

## Session list — VERIFIED
`POST https://services.mywellness.com/{facilityUrl}/Training/User/{userId}/ActivityHistory?_c=en-US`
- **`dateFrom`/`dateTo` are ignored.** Any body returns the 20 most recent sessions, newest first.
- Paging: `data.pager` is an opaque string, e.g. `"[21,1,1,1]|[202509100855180960#session_1356,,,]"`.
  Send it back as `{"pager": "<string>"}` to get the next 20 (verified once: returned idCr 1355…1336, pager `[41,…]`).
- `type` seen: only `"WorkoutSession"` (20 of 20). `onlyClasses: false` everywhere. Other types unknown → skip + count.
- `partitionDate` (int yyyyMMdd) + `hour`/`minute` = local start. `totalDurationInMinutes` = displayed duration.
  `duration` (string) = number of exercises, **not** minutes. `calories` = session total.
- Fixture: `activity_history_page1.json`.

## Session detail — VERIFIED
`POST https://services.mywellness.com/{facilityUrl}/Training/User/{userId}/GetPerformedWorkoutSessionByIdCr?_c=en-US`
Body `{"idCr": 1375, "partitionDate": 20251027}` (numbers).
- `data.startedOn`: `"2025-10-27 09:02:02 +01:00"` → format `yyyy-MM-dd HH:mm:ss XXX`.
- **`data.closedOn` is a date only** (`"2025-10-27"`), no time. Not usable as end.
- `data.doneSummary[]`: `{physicalProperty: "Calories", value: 473}`, `{physicalProperty: "Move", value: 941}`.
- `data.physicalActivities[]` (ordered by `position`): `physicalActivityName`, `equipmentName`, `equipmentType`
  (e.g. `UprightBike`, `SelectorizedLegPress`), `isCardio`, `physicalActivityType` (`CardioQuickStart` | `StrengthLoad`),
  `status: "Added"`, and **`performedPhysicalActivity`** (absent → exercise was planned, not done; skip it):
  - `doneOn`: end time of that exercise, same format as `startedOn` (with offset).
  - `data.data[]`: `{physicalProperty, value, unitOfMeasure, …}` — use **`value` (raw)**, never `formattedValue`.
    - Cardio: `Duration` value = **seconds** (1200 ↔ "20:00 min"; the `unitOfMeasure: "Minute"` label is misleading),
      `HDistance` value = **metres** (10189.005 ↔ "10.19 km"), `Calories` kcal, `Move`, `AvgRpm`, `AvgPower`,
      optional `AvgHr`/`MaxHr` (present when a HR source was connected).
    - Strength: `Calories`, `Move`, `TotalIsoWeight` (kg), optional `ExecutionMode`, `ExerciseCompliance`. **No Duration.**
      `data.steps[]` = sets with `IsoReps`, `IsoWeight`, `RestTime`.
  - `displayDurationDoneShort` ("6 min") exists for strength but is a rounded string → not used.

### Timeline (verified on 3 sessions)
- Cardio: `doneOn − Duration` == `startedOn` exactly (09:02:02 + 1200 s = 09:22:02). Cardio is always first (position 2).
- Strength: block = `[cardio.doneOn, last strength doneOn]`. Per-exercise start = previous exercise's `doneOn`.
- Session calories == cardio calories + Σ strength calories (473 = 230 + 243). Exact in all three sessions.
- Session end: last `doneOn` (09:52:45) vs `startedOn + totalDurationInMinutes` (09:50:02) differ by ~2–3 min.
  **Decision: end = last `doneOn`** (it is a real timestamp). `totalDurationInMinutes` is a fallback only when no exercise was performed.
- Offsets seen: `+02:00` (CEST, early/late October) and `+01:00` (CET, after the late-October fall-back) → offset-aware parsing works.

### Fixtures
`session_1375.json` — exact copy (values incl. sets), bulky keys removed: `constraints`, `muscles`, `pictureUrl`,
`pictureThumbUrl`, `videoUrl`, `muscleScores`, `physicalActivitiesToUpdateForManualProgression`. Parser must ignore unknown keys anyway.
`session_1374.json`, `session_1366.json` — exercise-level values exact (doneOn, Duration, HDistance, Calories, HR); set-level
`steps` not captured → `[]`, `TotalIsoWeight`/`AvgRpm`/`AvgPower` approximate. 1366 has HR on cardio and strength items
without ExecutionMode/Compliance, as observed. `session_detail_unknown.json` — response for a non-existent idCr.

## Mapping decisions derived from the above (feed into Mapper.kt)
1. Two sessions per visit: `BIKING_STATIONARY` (or other cardio type) `[startedOn, cardio.doneOn]` + `STRENGTH_TRAINING`
   `[cardio.doneOn, lastDoneOn]`. They touch, never overlap. If no cardio: one strength session `[startedOn, lastDoneOn]`.
   If no strength: one cardio session. Multiple cardio exercises: one cardio session spanning first start → last cardio doneOn,
   typed by the first machine, only if they are contiguous; otherwise fall back to one `OTHER_WORKOUT` session for the whole visit.
2. Calories: cardio session = Σ cardio `Calories`; strength = Σ strength `Calories`. Sanity: if the sum ≠ session `calories` ±2, still
   write (data is per-exercise and exact) but log a warning.
3. Distance: `DistanceRecord` for the cardio session from `HDistance` metres, only if > 0.
4. HR: available on some cardio items (`AvgHr`), not implemented (no WRITE_HEART_RATE).
5. Skip sessions with zero performed exercises, or with `type != "WorkoutSession"`.
