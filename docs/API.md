# Wayfare API

Sign in at `/api/auth/login` to obtain a bearer JWT, then use it on protected routes. Roles are `RIDER`, `DRIVER`, and `ADMIN`. Public signup accepts rider or driver only. Roles and participant ownership are enforced on the server.

| Route | Actions |
| --- | --- |
| /api/rides/quote | POST a pickup, dropoff, `from` and `to` points, and optional promo |
| /api/rides | POST a ride or GET ride history with a zero-based `page` |
| /api/rides/{id} | GET an accessible ride |
| /api/rides/{id}/status | PATCH an allowed `action` |
| /api/rides/{id}/location | PUT the assigned driver's current point |
| /api/rides/{id}/rating | POST integer `stars` and optional `feedback` |
| /api/driver/presence | PUT availability and last known point |
| /api/driver/requests | GET eligible nearby rides |
| /api/driver/stats | GET earnings, average rating, and presence |
| /api/drivers/ranking | GET the ranked driver list |
| /api/rides/{id}/payment | POST a PaymentIntent request |
| /api/payments/webhook | POST a Stripe-signed event |
| /api/rides/{id}/receipt | GET a paid receipt as PDF |
| /api/rides/{id}/receipt/email | POST to send a paid receipt to the rider's account email |
| /api/support | POST an in-app support or SOS ticket |
| /api/admin | GET recent ride operations and counts |
| /api/admin/promos | PUT a promotion and discount percentage |
| /api/admin/support/{id} | PATCH a ticket to resolved |

Points use `{ lat, lon }`. Fares use integer paise. The driver's lifecycle goes from `ACCEPT` to `ARRIVE`, `START`, and `COMPLETE`. Eligible unclaimed rides can be declined by individual drivers. A rider can cancel before the trip starts.

For live updates connect to `/ws` with STOMP, set `Authorization: Bearer <token>` on CONNECT, and subscribe to `/user/queue/live`. Notifications identify events. Fetch protected REST records after receiving them.
