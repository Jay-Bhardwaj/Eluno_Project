# POS v1: proposed scope and decisions

Hello Rohan, Meena, Dr Iyer, Vikram and Farhan,

For v1, I propose a focused till flow: customer and prescription lookup, clinically validated frame/lens carts, configured offers, GST invoices, terminal payment, Shopify order creation, full refunds, and a Shopify-based day-end report. This is the smallest release that protects clinical and finance controls while keeping normal checkout fast.

## Questions to resolve

| Owner | Question and why it matters |
|---|---|
| Finance | What reason-code list and approver policy should manual discounts use? Manager identity and a valid reason are required for audit. |

| Product/Finance | Does explicit `stackable: false` override the floor offer pitch? The data is the v1 source of truth: it does. The app chooses the greatest valid saving; priority breaks a tie. |

| Product/Shopify | Is a primary bill payer plus per-line customer reference acceptable for family billing? Shopify has one order customer; multi-customer prescriptions need consent, CRM and tax rules. |

| Finance | What is the cancellation/recovery process for an allocated invoice after an outage? Numbers will never be reused; an auditable cancelled/recovery state explains a gap. |

| Ops/Clinical | Who authorises a remake and bears lab cost? A remake must be a lens-only workflow with no frame sale or stock movement, but the commercial rule is not defined. |

| Finance/Ops | What cash-movement codes and escalation route apply to till variance? |

## Scope

**Must:** the reliable sale, refund and Shopify-reconciliation flow above. Unsafe lens configurations are blocked per eye, including progressive ADD and five-degree axis constraints. The day-end report reads Shopify as source of truth.

**Should:** cash sessions/paid-ins/paid-outs and variance alerts, sales-channel reporting, partial refunds, printable invoice and audit UI. **Stretch:** offer authoring, prescription extraction and anomaly flags. The beverage question and footfall camera are separate systems and out of scope. The daily founder email needs approved email infrastructure, recipient ownership, scheduling and monitoring, so it is deferred.

## Risks and rollout needs

The under-ten-second goal applies to the normal prepared checkout path; validation and durable recovery cannot be skipped. Before charging, v1 persists an invoice-backed checkout. Terminal retries reuse that invoice as `order_ref`. An ambiguous Shopify create is reconciled by the unique invoice note before any retry, preventing blind duplicate orders. This is safe recovery, not an exactly-once claim.

Production needs Shopify and terminal credentials/scopes, role provisioning, printer and hardware testing, monitoring, backup/recovery, reconciliation dashboards, network incident procedures, privacy controls for prescriptions, staff training and a pilot-store rollout. I recommend piloting one store, measuring checkout time and finance reconciliation, then expanding progressively.
