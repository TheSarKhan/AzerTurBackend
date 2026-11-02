# Operations request workspace

The approved request queue now loads customer identities, service selections, dates, assignment and actual offer/contract/customer-request stages from GET /api/v1/operations/request-workspace. No sample people, counts or activity are injected into the application.

POST /api/v1/operations/bookings/{id}/assignment takes assign and expectedOwner. The authenticated employee can take an unassigned booking or release their own. Booking locking and expected-owner comparison prevent overwriting concurrent assignment. Each change writes an audit entry. No arbitrary new staff accounts or permissions are created.

The customer and public roles cannot access the workspace or assignment endpoints. The response excludes internal notes, draft content, supplier data and contract files. This is still office-only customer data.

The UI provides local search across references, customers, routes and services, date/status/owner filters, six-row pagination, request selection, next-step navigation, priority filters and recent actual audit events. Seven-day confirmed departures use the Asia/Baku calendar. Status counts are computed from the same complete response. List data is fetched without the old 200-booking limit; at larger volumes, server-side search and pagination will be required.

Navigation opens the existing request review and offer/contract/payment workflow. No offer is sent by selecting or opening a request. Upcoming departure indicators require CONFIRMED booking status. A paid or accepted-contract status is not fabricated. Only the stored start date is shown; an end date is not inferred from a template.

Partners, staff account administration, notification delivery and deposit allocation remain separate modules. Existing sidebar destinations remain available; no inactive partner link was added.

Validation: Maven tests, local integration tests/request_workspace.py (authorization, data, date serialization, assignment persistence, stale writes and audit). Dashboard production build and browser checks of search, no-results, priority filters, selection and opening the existing review; desktop and 390px viewport screenshots in approved design archive.
