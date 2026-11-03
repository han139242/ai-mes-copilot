# AI business capabilities

[简体中文](FEATURES.md) · English · [Project overview](../README.en.md)

AI-MES Copilot maps natural-language requests to registered business capabilities and executes them through existing domain services. The model interprets intent and organizes a plan; business code performs inventory calculations, state transitions, permission checks, and database writes.

## Queries and analysis

The catalog contains 20 capabilities:

1. Inventory receipts, issues, and balances.
2. Current inventory quantities.
3. Safety-stock shortages.
4. Work-order details.
5. Work-start prerequisite checks.
6. Plans for resolving work-start blockers.
7. Daily output and defects.
8. Defect analysis.
9. Lot traceability.
10. Scheduling and capacity.
11. Order progress.
12. Production-order scheduling simulations.
13. Equipment-failure impact.
14. Additional-capacity estimates.
15. Bill-of-materials quantities.
16. Quality yield.
17. Work-order lists.
18. Customer orders.
19. Pending inspections.
20. General business-data queries.

## Direct business actions

The catalog contains 19 actions:

1. Create a production order.
2. Schedule a production order.
3. Generate work orders.
4. Release work orders.
5. Generate dispatch documents.
6. Release dispatch documents.
7. Start an operation task.
8. Submit a production report.
9. Start production.
10. Generate a material issue document.
11. Transfer issued materials to a line-side warehouse.
12. Confirm an outbound transaction.
13. Confirm an inbound transaction.
14. Submit inspection results already recorded in the system.
15. Generate a finished-goods receipt document.
16. Generate a sales outbound document.
17. Generate an outgoing inspection document.
18. Confirm shipment.
19. Review a production report.

Definitions are maintained in [`AiCapabilityCatalog.java`](../workspace/idea/ems/src/main/java/com/neuedu/ems/service/ai/AiCapabilityCatalog.java). General query scope is constrained by `AiDataResourceCatalog` and the current account's permissions.

## How an action runs

1. The user describes a goal. AI identifies the business object, parameters, and required capabilities.
2. The system reads actual documents, checks permissions and prerequisites, and asks for clarification or shows candidates when information is missing.
3. The page presents an action preview. The user confirms it before execution.
4. Domain services enforce transactions, inventory rules, document states, and duplicate-request checks, then record the result.
5. The assistant verifies the business outcome and suggests a supported next step.

A blocked multi-step task shows completed steps and the failure reason, and can continue according to the actual business state. Automation rules run on events or schedules within their configured authorization scope.

## Quality data and planning boundaries

Quality personnel perform physical measurements and enter actual piece-level values. Inspection submission uses those existing values; AI does not generate measurement data. The model does not execute arbitrary SQL or replace actual document, inventory, and inspection states with a user's verbal assertion.

Scheduling simulations read a business snapshot and compare alternatives. Hypothetical results are kept separate from effective scheduling; actual plan changes use the corresponding business action flow.
