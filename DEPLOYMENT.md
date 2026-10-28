# Free deployment: Render

The deployed demo is a **single Render Free Docker web service**. Spring Boot serves both the cashier UI and `/api/*`; the supplied Python mock starts on `127.0.0.1` in the same private container. This preserves the real checkout/refund integrations without making the mock public or requiring CORS.

## Before creating the service

1. Push this project to a GitHub repository. Do not include `pos-state.json`, `checkout-state.json`, `.env`, or credentials; `.gitignore` already excludes them.
2. Create a free account at [Render](https://render.com/) and connect the GitHub repository.

## Render setup

1. Select **New → Blueprint** and choose the repository. Render detects `render.yaml`.
2. Confirm the service is named `eluno-pos-demo`, uses the **Free** plan, and deploy it.
3. Render builds `Dockerfile`, runs `/app/docker-start.sh`, and assigns `PORT` automatically.
4. After deployment, open the generated `https://<service>.onrender.com` URL.
5. Verify:

```text
https://<service>.onrender.com/api/health
```

Expected response:

```json
{"ok":true}
```

Then visit the root URL and demonstrate a customer lookup, validation, checkout, refund, and day-end report.

## Environment variables

| Name | Render value | Purpose |
| --- | --- | --- |
| `ELUNO_MOCK_URL` | `http://127.0.0.1:8787` | Keeps the mock private in the same container. |
| `PORT` | Render-managed | Spring Boot public HTTP port. Do not set manually. |
| `ELUNO_ALLOWED_ORIGIN` | Leave empty | Not needed for same-origin deployment. Set to one exact HTTPS frontend URL only if moving the UI to a separate host. |
| `ELUNO_BUSINESS_DATE` | Optional ISO date | Pins business date for deterministic demonstrations. |

## Important free-tier limitation

Render Free web services have an ephemeral filesystem and may spin down after inactivity. On restart/redeploy the supplied mock and `pos-state.json`/`checkout-state.json` reset. This is acceptable for a reproducible assignment demonstration, not production persistence.

For production, move invoice and recovery state to a managed transactional database, add authenticated staff identity, monitoring, reconciliation tooling, and a real payment/Shopify integration.
