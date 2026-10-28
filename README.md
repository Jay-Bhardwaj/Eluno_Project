# Eluno POS

Java 21 / Spring Boot implementation of the Eluno till Must scope, now with a responsive browser-based cashier dashboard at the application root. Shopify, SMT and the terminal are reached only through `client/ApiClient`; business rules are in `service/PosService`. The supplied starter kit is retained unchanged.

## Run

Prerequisites: Java 21, Python 3.8+, Maven 3.9+. On Windows run:

```powershell
.\run-local.ps1
```

The command starts the supplied mock (deterministic `--no-flake`) and Spring Boot at `http://localhost:8080`. Ctrl+C stops both. To manually build:

```powershell
mvn -DskipTests package
```

Open `http://localhost:8080` after startup to use the cashier dashboard. It uses the same live REST API described below: no frontend success state is shown unless the backend operation succeeds.

## Cashier dashboard

The dashboard provides store and cashier selection, customer/prescription lookup, frame and lens selection, a cart with validation/tax/discount totals, terminal checkout, invoice/result display, full refund, credit note display and a Shopify-backed day-end report. It is intentionally plain HTML, CSS and JavaScript served by Spring Boot so no additional frontend build stack is required.

## Endpoints

- `GET /api/health`
- `GET /api/customers/lookup?phone=...`
- `GET /api/stores`, `GET /api/staff?store=HSR`
- `GET /api/products/frames`, `GET /api/products/lenses`
- `POST /api/cart/validate`, `POST /api/checkout/preview`, `POST /api/checkout`
- `POST /api/refunds/{orderId}/full`
- `GET /api/reports/day-end?locationId=...&date=YYYY-MM-DD`

`demo-requests.http` has ready-to-run examples for VS Code REST Client.

## Core behaviour

The service retrieves customers, products, stock, orders and refunds from Shopify; prescriptions/staff from SMT; and charges/refunds from the terminal. It validates both eyes against each selected variant’s `rx_limits`, including ADD and five-degree axis rules. Product, offer, HSN and GST data are read from the supplied seed configuration. Money is integer paise internally, converted to decimal strings only for Shopify.

Offers honour active/date/tag/lens/index/coating/pair/manager-rule settings. Stackable offers form one candidate; each non-stackable offer is a competing candidate. The greatest valid saving is chosen. GST is extracted per discounted inclusive line using half-up rounding, then split into CGST/SGST.

Invoice series and checkout states persist locally in `pos-state.json` and `checkout-state.json`; numbers are allocated per store and never reused. The invoice is the terminal idempotency key. 429/503 calls retry within a bounded budget. If payment succeeds but Shopify order creation fails, state is retained as `ORDER_RECOVERY_REQUIRED`; the implementation does not automatically recharge or blindly recreate the order.

Full refunds use the original Shopify line and transaction IDs; frames restock to the billing store and lenses use `no_restock`. Day-end queries Shopify, retains original sales and reports refunds separately.



Automated tests are deliberately deferred by the current assignment brief. Also deferred: cash till, partial refunds, family multi-customer billing, remake authorisation/cost policy, printable invoices, scheduled email, production authentication/monitoring, and an operator reconciliation endpoint for ambiguous Shopify creates. See `STAKEHOLDER_REPLY.md`.

## Deployment

The repository includes a Docker-based single-service free-hosting configuration for Render. Spring Boot serves the UI/API while the Python mock stays private inside the same container. See `DEPLOYMENT.md` for the exact setup steps and the important free-tier state-reset limitation.

