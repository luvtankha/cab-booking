# Wayfare · Cab Booking

Wayfare is a Java 21 and Spring Boot ride-booking application with separate rider, driver, and operations dashboards. The React frontend includes a Leaflet trip map. The backend stores rides and payments in PostgreSQL and sends private updates using authenticated Spring STOMP over WebSocket.

## Run locally

Use Java 21, Maven 3.9+, and Node.js 24. In two terminals, run `cd server && mvn spring-boot:run` and `cd client && npm ci && npm run dev`. Open the Vite URL. Local storage defaults to file-backed H2 for setup. Set `DATABASE_URL`, `DB_USER`, and `DB_PASSWORD` for PostgreSQL.

1. Register a driver and select **Use sample location**.
2. In a separate browser profile or a private window, register a rider. Use **Sample route**, get a fare estimate, and request a ride.
3. Accept it from the driver's dashboard, then mark arrival, start, and complete the ride.
4. To test a no-charge receipt locally, start the backend with `DEMO_PAYMENTS=true` and select **Record demo payment**. This flag is rejected in the Render profile.
5. Submit a review and inspect driver earnings and rankings. To access operations, set `ADMIN_EMAIL` and `ADMIN_PASSWORD` before the initial admin startup, then sign in as that user.

## Integrations

| Feature | Configuration |
| --- | --- |
| Route plotting and ETA | Leaflet draws pins and the route. Set `ROUTER_URL` to a trusted OSRM instance that covers the service region for road distance, ETA, and geometry. The default is a labeled estimate. [OSRM API](https://project-osrm.org/docs/v5.24.0/api/) |
| Map tile service | The default is OpenStreetMap. Set `VITE_TILE_URL` and `VITE_TILE_ATTRIBUTION` for a hosted rollout. [OpenStreetMap tile usage](https://operations.osmfoundation.org/policies/tiles/) |
| Nearby matching | Available drivers with a recent location receive rides within a 15 km radius and can accept or decline them. Assigned drivers can share positions while a ride is active. |
| Google sign-in | Add `google` to `SPRING_PROFILES_ACTIVE`. Set the OAuth client ID and secret and register `https://YOUR_HOST/login/oauth2/code/google`. New verified Google accounts are riders. Existing password accounts sign in with their password. [Google web server OAuth](https://developers.google.com/identity/protocols/oauth2/web-server) |
| Stripe payments | Set `STRIPE_SECRET_KEY`, `STRIPE_PUBLISHABLE_KEY`, and `STRIPE_WEBHOOK_SECRET`. Configure `POST /api/payments/webhook` for `payment_intent.succeeded`. Card details stay in Stripe Elements. An authenticated webhook must match the stored intent, amount, currency, and ride before a receipt is available. [Stripe webhooks](https://docs.stripe.com/webhooks) |
| Receipts | Download PDFs after receipt eligibility. Supply `SMTP_HOST`, authentication settings, and `RECEIPT_FROM` to enable JavaMail delivery. |
| Support and SOS | Active rides can send an alert to the operations dashboard. The app does not dispatch emergency services. Admins can resolve tickets and manage promo codes. |

## Verification and hosting

Run `mvn -f server/pom.xml package` for the API suite. Then run `npm run test:e2e` in `client` after installing Playwright Chromium and WebKit. Tests use synthetic accounts, local payment simulation, and a signed Stripe webhook fixture. CI also uses PostgreSQL 17 and builds the Docker image. External routing, real Google sessions, Stripe transactions, and SMTP delivery require credentials or a provider.

The [Render Blueprint](render.yaml) serves the client and API behind one HTTPS origin and wires PostgreSQL over the private network. Set production secrets through the hosting environment. The default free database is intended for short-lived demos. The optional [Nginx template](nginx.conf) proxies REST and WebSocket requests on a self-hosted deployment. [API overview](docs/API.md)
