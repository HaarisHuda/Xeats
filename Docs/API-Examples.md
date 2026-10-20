# Xeats API examples

These examples use the contract in `openapi.yaml` and the synthetic `demo` profile. Values such as UUIDs, times, and pickup codes are illustrative. `amountMinor` is integer paise for INR. Do not log or share a pickup code; only the employee and assigned counter staff need it.

## Employee: discover and place one meal

The employee first requests `GET /api/v1/auth/csrf`, then signs in with that token. Login rotates the token, so fetch `/auth/csrf` again before mutations. The [employee checkout HTTP journey](../scripts/employee-checkout.http) follows this sequence.

```http
GET /api/v1/counters/00000000-0000-4000-8000-000000000301/order-options
```

```json
{
  "counter": {
    "id": "00000000-0000-4000-8000-000000000301",
    "name": "North Thali",
    "vendorId": "00000000-0000-4000-8000-000000000211",
    "pickupInstructions": "Collect at the North Thali counter",
    "active": true,
    "acceptingOnlineOrders": true,
    "item": {
      "id": "00000000-0000-4000-8000-000000000501",
      "name": "Veg Thali",
      "description": "Rice, vegetables, and dal",
      "category": "Meal",
      "price": { "amountMinor": 8500, "currency": "INR" },
      "availability": "AVAILABLE"
    }
  },
  "timeZone": "Asia/Kolkata",
  "selectableWindows": [
    {
      "id": "00000000-0000-4000-8000-000000000501",
      "startAt": "2026-10-20T06:05:00Z",
      "endAt": "2026-10-20T06:10:00Z",
      "onlineCapacity": 4,
      "allocated": 1,
      "remaining": 3,
      "estimatedReadyAt": "2026-10-20T06:03:00Z",
      "estimateIsApproximate": true,
      "expectedOnsiteWaitMinutes": null,
      "onsiteWaitUnavailableReason": "WALK_IN_AND_HANDOVER_TIMES_NOT_MEASURED"
    }
  ],
  "recommendedWindowId": "00000000-0000-4000-8000-000000000501",
  "noOptionsReason": null
}
```

The employee confirms exactly one meal from one counter with a UUID `Idempotency-Key`:

```http
POST /api/v1/orders
Content-Type: application/json
X-XSRF-TOKEN: <authenticated-session-token>
Idempotency-Key: 7a223217-c29d-4c18-9e24-164fe205f701

{
  "counterId": "00000000-0000-4000-8000-000000000301",
  "pickupSlotId": "00000000-0000-4000-8000-000000000501"
}
```

`201 Created` includes the owner-readable pickup code. A same-key retry returns the original order (`200 OK`); a different request with the same key conflicts.

```json
{
  "id": "00000000-0000-4000-8000-000000000601",
  "counterId": "00000000-0000-4000-8000-000000000301",
  "counterName": "North Thali",
  "pickupInstructions": "Collect at the North Thali counter",
  "itemName": "Veg Thali",
  "itemPrice": { "amountMinor": 8500, "currency": "INR" },
  "pickupWindow": {
    "id": "00000000-0000-4000-8000-000000000501",
    "startAt": "2026-10-20T06:05:00Z",
    "endAt": "2026-10-20T06:10:00Z"
  },
  "estimatedReadyAt": "2026-10-20T06:03:00Z",
  "estimateIsApproximate": true,
  "expectedOnsiteWaitMinutes": null,
  "onsiteWaitUnavailableReason": "WALK_IN_AND_HANDOVER_TIMES_NOT_MEASURED",
  "status": "ACCEPTED",
  "version": 1,
  "placedAt": "2026-10-20T05:55:00Z",
  "acceptedAt": "2026-10-20T05:55:00Z",
  "preparingAt": null,
  "readyAt": null,
  "collectedAt": null,
  "expiredAt": null,
  "expiryReason": null,
  "overdue": false,
  "simulatedPaymentStatus": "SIMULATED_SUCCEEDED",
  "pickupToken": { "code": "7X4M9D2K6P", "redeemable": true }
}
```

## Counter staff: fulfill and collect

The [staff fulfillment HTTP journey](../scripts/staff-fulfillment.http) uses its own session, refreshes CSRF after login, and accepts the order ID and code from the employee journey. Queue responses intentionally omit the code.

```json
{
  "items": [
    {
      "id": "00000000-0000-4000-8000-000000000601",
      "counterId": "00000000-0000-4000-8000-000000000301",
      "itemName": "Veg Thali",
      "itemPrice": { "amountMinor": 8500, "currency": "INR" },
      "status": "ACCEPTED",
      "version": 1,
      "employeeDisplayLabel": "employee.demo.01"
    }
  ],
  "page": 0,
  "size": 25,
  "totalItems": 1,
  "totalPages": 1
}
```

Staff transitions send the order version from the `ETag` as `If-Match`:

```http
POST /api/v1/staff/orders/00000000-0000-4000-8000-000000000601/preparing
If-Match: "1"
X-XSRF-TOKEN: <staff-session-token>
```

```http
POST /api/v1/staff/orders/00000000-0000-4000-8000-000000000601/ready
If-Match: "2"
X-XSRF-TOKEN: <staff-session-token>
```

After the meal is ready, staff redeem the code at its serving counter:

```http
POST /api/v1/staff/counters/00000000-0000-4000-8000-000000000301/pickup-redemptions
Content-Type: application/json
X-XSRF-TOKEN: <staff-session-token>

{ "code": "7X4M9D2K6P" }
```

The `200 OK` fulfillment response changes the status to `COLLECTED`, advances the version, includes the actual collection timestamp, and does not echo the code.

## Counter dashboard report

`GET /api/v1/staff/counters/{counterId}/report?startDate=2026-10-20&endDate=2026-10-20` uses inclusive food-court-local dates (at most 31). Event totals use their corresponding event timestamps; hourly bins use `placedAt`.

```json
{
  "timeZone": "Asia/Kolkata",
  "startDate": "2026-10-20",
  "endDate": "2026-10-20",
  "ordersAccepted": 12,
  "ordersCollected": 10,
  "ordersCollectedOnTime": 9,
  "ordersExpired": 2,
  "simulatedAcceptedValue": { "amountMinor": 102000, "currency": "INR" },
  "simulatedCollectedValue": { "amountMinor": 85000, "currency": "INR" },
  "mealsCollectedByItem": [
    {
      "itemName": "Veg Thali",
      "itemPrice": { "amountMinor": 8500, "currency": "INR" },
      "count": 10,
      "simulatedValue": { "amountMinor": 85000, "currency": "INR" }
    }
  ],
  "hourlyDistribution": [
    { "localHour": 0, "ordersPlaced": 0 },
    { "localHour": 1, "ordersPlaced": 0 },
    { "localHour": 2, "ordersPlaced": 0 },
    { "localHour": 3, "ordersPlaced": 0 },
    { "localHour": 4, "ordersPlaced": 0 },
    { "localHour": 5, "ordersPlaced": 0 },
    { "localHour": 6, "ordersPlaced": 0 },
    { "localHour": 7, "ordersPlaced": 0 },
    { "localHour": 8, "ordersPlaced": 0 },
    { "localHour": 9, "ordersPlaced": 0 },
    { "localHour": 10, "ordersPlaced": 2 },
    { "localHour": 11, "ordersPlaced": 6 },
    { "localHour": 12, "ordersPlaced": 4 },
    { "localHour": 13, "ordersPlaced": 0 },
    { "localHour": 14, "ordersPlaced": 0 },
    { "localHour": 15, "ordersPlaced": 0 },
    { "localHour": 16, "ordersPlaced": 0 },
    { "localHour": 17, "ordersPlaced": 0 },
    { "localHour": 18, "ordersPlaced": 0 },
    { "localHour": 19, "ordersPlaced": 0 },
    { "localHour": 20, "ordersPlaced": 0 },
    { "localHour": 21, "ordersPlaced": 0 },
    { "localHour": 22, "ordersPlaced": 0 },
    { "localHour": 23, "ordersPlaced": 0 }
  ],
  "timings": {
    "acceptedToPreparing": { "sampleCount": 10, "averageMinutes": 1.25, "p95Minutes": 2.0 },
    "preparingToReady": { "sampleCount": 10, "averageMinutes": 3.4, "p95Minutes": 5.0 },
    "readyToCollected": { "sampleCount": 10, "averageMinutes": 1.1, "p95Minutes": 2.0 },
    "placedToCollected": { "sampleCount": 10, "averageMinutes": 5.75, "p95Minutes": 8.0 }
  }
}
```

Simulated order values are not settled funds, settlement, or revenue. Empty timing samples return `sampleCount: 0` with null average and p95.

## Live event and safe error

SSE messages are notifications to refetch the authorized resource; the event stream is not replayed after reconnect:

```text
event: ORDER_STATUS_CHANGED
id: 519fba44-3f54-4906-98af-59d1022f5728
data: {"entityId":"00000000-0000-4000-8000-000000000601","counterId":"00000000-0000-4000-8000-000000000301","entityVersion":4,"occurredAt":"2026-10-20T06:06:00Z"}
```

```json
{
  "type": "urn:xeats:problem:active-order-exists",
  "title": "Conflict",
  "status": 409,
  "code": "ACTIVE_ORDER_EXISTS",
  "detail": "An active order already exists for this counter."
}
```
