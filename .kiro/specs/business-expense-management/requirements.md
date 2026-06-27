# Requirements Document

## Introduction

The Business Expense Management System (BEMS) is a multi-tenant, production-quality web application built with Java Spring Boot. It enables multiple businesses to manage employee expense submissions, multi-level approvals, reimbursements, budget enforcement, and financial reporting within a single shared platform. Each business operates independently, isolated by its Business ID. The system supports five core roles — Business Owner, Admin, Manager, Accountant, and Employee — each with distinct responsibilities and access rights.

---

## Glossary

- **BEMS**: Business Expense Management System — the platform described in this document.
- **Platform_Super_Admin**: A platform-level administrator who manages all registered businesses in BEMS and has no business-specific role.
- **Business_Owner**: A user with full control over a single business tenant, including overriding approvals and configuring all policies.
- **Admin**: A user who manages users, departments, expense categories, and spending policies within a single business; cannot approve expenses.
- **Manager**: A user who manages employees and approves expenses within their assigned department.
- **Accountant**: A user who verifies Manager-approved expenses, processes reimbursements, and generates financial reports.
- **Employee**: A user who submits, edits, and tracks their own expenses; belongs to exactly one department within one business.
- **Business**: A top-level tenant entity identified by a unique Business ID; all data is scoped to its Business ID.
- **Department**: An organisational unit within a Business; each Department belongs to exactly one Business.
- **Expense**: A monetary claim submitted by an Employee for a business-related expenditure.
- **Expense_Category**: A named classification for an Expense (e.g., Travel, Meals, Accommodation) defined per Business.
- **Approval_Chain**: The ordered sequence of approvers an Expense must pass through before reaching the Reimbursed status, determined by the expense amount.
- **Delegate_Approver**: A Manager-designated substitute who can approve expenses on the Manager's behalf during absence.
- **Spending_Policy**: A configurable rule defining budget limits — per Employee (monthly), per Department (monthly), or per Expense_Category (per transaction).
- **Reimbursement**: The manual payment action recorded by the Accountant when an expense has completed the full Approval_Chain.
- **Audit_Log**: An immutable, timestamped record of every create, update, status-change, and delete action performed within the system.
- **Soft_Delete**: A deletion mechanism that marks a record as inactive without removing it from the database, preserving the audit trail.
- **Attachment**: A receipt or supporting document uploaded by the Employee; must be PDF, JPG, JPEG, or PNG and no larger than 10 MB.

---

## Requirements

---

### Requirement 1: Multi-Tenant Business Registration

**User Story:** As a Platform_Super_Admin, I want to register and manage businesses on the platform, so that each business operates as an isolated tenant.

#### Acceptance Criteria

1. THE Platform_Super_Admin SHALL create a new Business by providing a unique business name, registered country, currency code, and primary contact email.
2. WHEN a Business is created, THE BEMS SHALL assign a globally unique Business ID and set the Business status to Active.
3. THE Platform_Super_Admin SHALL deactivate or reactivate any Business on the platform.
4. WHEN a Business is deactivated, THE BEMS SHALL prevent all users of that Business from authenticating until the Business is reactivated.
5. THE BEMS SHALL isolate all data queries by Business ID so that users of one Business cannot access data belonging to another Business.
6. THE Platform_Super_Admin SHALL view a paginated list of all Businesses with their name, status, and creation date.

---

### Requirement 2: User Management

**User Story:** As an Admin, I want to create and manage user accounts within my business, so that employees can access the system with appropriate roles.

#### Acceptance Criteria

1. THE Admin SHALL create a user account by providing a full name, business email, department, and exactly one role from: Business_Owner, Admin, Manager, Accountant, Employee.
2. WHEN a user account is created, THE BEMS SHALL send an email to the new user containing a one-time activation link valid for 48 hours.
3. THE Admin SHALL assign a user to exactly one Department within the same Business.
4. THE Admin SHALL update a user's role, department, or contact details at any time while the user's account is Active.
5. THE Admin SHALL deactivate a user account; THE BEMS SHALL apply Soft_Delete and prevent the deactivated user from authenticating.
6. WHEN a user is deactivated, THE BEMS SHALL reassign all pending Expenses requiring that user's approval to the next approver in the Approval_Chain or to the Business_Owner if no next approver exists.
7. THE BEMS SHALL enforce that each Business has at most one active Business_Owner at any time.
8. IF a user attempts to access a resource not permitted by their role, THEN THE BEMS SHALL return an HTTP 403 response with a descriptive error message.

---

### Requirement 3: Department Management

**User Story:** As an Admin, I want to create and manage departments within my business, so that expenses and budgets can be organised by organisational unit.

#### Acceptance Criteria

1. THE Admin SHALL create a Department by providing a unique name within the Business and an optional description.
2. THE Admin SHALL update a Department's name and description.
3. THE Admin SHALL deactivate a Department; THE BEMS SHALL apply Soft_Delete and prevent new users or Expenses from being assigned to the deactivated Department.
4. IF a Department with active Employees is deactivated — determined by the system's active-employee flag for the Department — THEN THE BEMS SHALL require the Admin to reassign all active Employees to another Department before completing the deactivation, regardless of whether a separate employee count shows zero; IF the system's active-employee flag and the employee count are in conflict for any Department, THEN THE BEMS SHALL treat the conflict as a data integrity error, block the deactivation, and raise an alert requiring Administrator review before any deactivation action can proceed.
5. THE BEMS SHALL display all Departments with their active Employee count and current-month budget utilisation.

---

### Requirement 4: Expense Category Management

**User Story:** As an Admin, I want to define expense categories and per-category spending limits, so that the business can enforce category-specific policies.

#### Acceptance Criteria

1. THE Admin SHALL create an Expense_Category by providing a unique category name within the Business and an optional description.
2. THE Admin SHALL set a per-transaction spending limit for any Expense_Category; WHEN this limit is set, THE BEMS SHALL prevent submission of an Expense that exceeds the category limit.
3. THE Admin SHALL update or remove the per-transaction limit on any Expense_Category.
4. THE Admin SHALL deactivate an Expense_Category; THE BEMS SHALL apply Soft_Delete and prevent new Expenses from referencing the deactivated category.
5. THE Business_Owner SHALL also perform all actions described in Acceptance Criteria 1 through 4 of this requirement.

---

### Requirement 5: Spending Policy Management

**User Story:** As an Admin or Business_Owner, I want to define per-employee and per-department monthly spending limits, so that budget overruns are prevented before expenses are submitted.

#### Acceptance Criteria

1. THE Admin SHALL set a monthly spending limit for any individual Employee within the Business.
2. THE Admin SHALL set a monthly spending budget for any Department within the Business.
3. WHEN an Employee submits an Expense, THE BEMS SHALL calculate the total approved and pending Expense amounts for that Employee in the current calendar month and reject the submission IF the new Expense would cause the total to exceed the Employee's monthly limit.
4. WHEN an Employee submits an Expense, THE BEMS SHALL calculate the total approved and pending Expense amounts for the Employee's Department in the current calendar month and reject the submission IF the new Expense would cause the total to exceed the Department's monthly budget.
5. IF a submission is rejected due to a spending limit breach, THEN THE BEMS SHALL return an error message that states the applicable limit, the current total, and the amount by which the limit would be exceeded; THE BEMS SHALL provide these details only when the submission is actually rejected, not on intermediate checks during drafting.
6. THE Business_Owner SHALL also perform all actions described in Acceptance Criteria 1 and 2 of this requirement.

---

### Requirement 6: Expense Submission

**User Story:** As an Employee, I want to submit expense claims with supporting receipts, so that I can be reimbursed for business-related expenditures.

#### Acceptance Criteria

1. THE Employee SHALL create an Expense in Draft status by providing: title, amount (positive decimal up to two decimal places), expense date, Expense_Category, and an optional description.
2. WHEN an Expense amount exceeds ₹500, THE BEMS SHALL require the Employee to upload at least one Attachment before the Expense can be submitted.
3. THE BEMS SHALL accept Attachments only in PDF, JPG, JPEG, or PNG format and only if the file size does not exceed 10 MB; IF a file fails either check, THEN THE BEMS SHALL reject the upload and return a descriptive error message.
4. THE Employee SHALL submit a Draft Expense; WHEN submitted, THE BEMS SHALL set the status to Submitted and record the submission timestamp.
5. WHEN an Expense is submitted, THE BEMS SHALL determine the Approval_Chain based on the expense amount:
   - Amounts less than ₹5,000: Manager only.
   - Amounts from ₹5,000 to ₹50,000 (inclusive): Manager, then Accountant.
   - Amounts above ₹50,000: Manager, then Accountant, then Business_Owner.
6. WHEN an Expense status changes to Submitted, THE BEMS SHALL send an in-app and email notification to the first approver in the Approval_Chain.
7. THE BEMS SHALL reject submission of an Expense whose expense date is more than 30 calendar days before the submission date; IF this condition is met, THEN THE BEMS SHALL return an error message stating the policy.
8. THE Employee SHALL edit any field of a Draft Expense before submission.
9. THE Employee SHALL delete a Draft Expense; THE BEMS SHALL apply Soft_Delete.

---

### Requirement 7: Expense Editing and Resubmission

**User Story:** As an Employee, I want to edit and resubmit a rejected expense, so that I can correct the issues identified by the approver.

#### Acceptance Criteria

1. WHEN an Expense status is Rejected, THE Employee who owns the Expense SHALL edit the Expense fields and attachments.
2. WHEN the Employee explicitly submits an edited Rejected Expense by clicking the submit action, THE BEMS SHALL reset the Approval_Chain, set the status to Submitted, and notify the first approver; THE BEMS SHALL NOT send any approver notification at any point while the Employee is editing or saving the Expense prior to the explicit submit action.
3. WHILE an Expense status is Submitted or in any intermediate approval status, THE BEMS SHALL prevent the owning Employee from editing the Expense.
4. THE BEMS SHALL record the resubmission count and timestamp on every Expense that has been resubmitted at least once.

---

### Requirement 8: Manager Approval

**User Story:** As a Manager, I want to approve or reject expense submissions from my department, so that only valid and policy-compliant expenses advance in the workflow.

#### Acceptance Criteria

1. WHEN a Submitted Expense belongs to an Employee in the Manager's Department, THE Manager SHALL approve or reject the Expense; IF the Expense remains in Submitted status without any action from the Manager or an active Delegate_Approver for more than the configured escalation threshold (default: 7 calendar days, configurable per Business by the Admin or Business_Owner), THEN THE BEMS SHALL automatically escalate the Expense by notifying the Admin and Business_Owner and flagging the Expense as Escalated; escalation SHALL NOT change the Expense status from Submitted and SHALL NOT automatically reassign the approver.
2. WHEN the Manager approves an Expense, THE BEMS SHALL set the status to Manager_Approved and, IF the Approval_Chain requires further review, notify the next approver in the chain.
3. WHEN the Manager rejects an Expense, THE BEMS SHALL set the status to Rejected, record the rejection reason provided by the Manager, and notify the owning Employee via in-app and email notification.
4. THE Manager SHALL provide a mandatory comment when rejecting an Expense.
5. WHEN a Manager is unavailable, THE Manager SHALL designate a Delegate_Approver from the same Business; THE Delegate_Approver SHALL have the same approval authority as the Manager for the delegation period.
6. THE Manager SHALL set a start date and end date for a delegation; THE BEMS SHALL treat the delegation as active exclusively from 00:00:00 UTC on the start date through 23:59:59 UTC on the end date (inclusive); THE BEMS SHALL automatically deactivate the delegation at the end of the end date and SHALL NOT apply the delegation outside this date range under any circumstances.
7. IF no active Delegate_Approver exists and the Manager has not acted on a pending Expense beyond the configured escalation threshold (see Acceptance Criterion 1), THEN THE BEMS SHALL additionally send an in-app and email notification to the Admin and Business_Owner; the Expense SHALL remain in Submitted status until the Manager or an active Delegate_Approver acts.

---

### Requirement 9: Accountant Verification

**User Story:** As an Accountant, I want to verify Manager-approved expenses before processing reimbursement, so that financial accuracy is maintained.

#### Acceptance Criteria

1. WHEN an Expense status is Manager_Approved and the Approval_Chain includes the Accountant, THE Accountant SHALL approve or reject the Expense.
2. WHEN the Accountant approves an Expense, THE BEMS SHALL set the status to Accountant_Approved and, IF the Approval_Chain requires further review (amount above ₹50,000), notify the Business_Owner.
3. WHEN the Accountant rejects an Expense, THE BEMS SHALL set the status to Rejected, record the rejection reason, and notify the owning Employee via in-app and email notification.
4. THE Accountant SHALL provide a mandatory comment when rejecting an Expense.
5. THE Accountant SHALL view all Expenses in Manager_Approved status across all Departments within the Business.

---

### Requirement 10: Business Owner Approval and Override

**User Story:** As a Business_Owner, I want to approve high-value expenses and override any pending approval, so that I maintain ultimate control over business expenditures.

#### Acceptance Criteria

1. WHEN an Expense status is Accountant_Approved and the expense amount exceeds ₹50,000, THE Business_Owner SHALL approve or reject the Expense.
2. WHEN the Business_Owner approves an Expense in the final Approval_Chain step, THE BEMS SHALL set the status to Fully_Approved.
3. WHEN the Business_Owner rejects an Expense, THE BEMS SHALL set the status to Rejected, record the rejection reason, and notify the owning Employee via in-app and email notification.
4. THE Business_Owner SHALL override any Expense in any intermediate approval status (Submitted, Manager_Approved, or Accountant_Approved) and set it directly to Fully_Approved.
5. WHEN the Business_Owner performs an override, THE BEMS SHALL record the override action, timestamp, and comment in the Audit_Log.
6. THE Business_Owner SHALL provide a mandatory comment when exercising an override.

---

### Requirement 11: Reimbursement Processing

**User Story:** As an Accountant, I want to mark fully-approved expenses as reimbursed and record payment details, so that employees can track their reimbursement status.

#### Acceptance Criteria

1. WHEN an Expense status is Fully_Approved, THE Accountant SHALL mark the Expense as Reimbursed by recording a payment reference, payment method description, and payment date.
2. WHEN an Expense is marked as Reimbursed, THE BEMS SHALL set the status to Reimbursed and attempt to send an in-app and email notification to the owning Employee; IF a notification delivery fails, THE BEMS SHALL complete the reimbursement regardless and log the notification failure for retry.
3. THE BEMS SHALL prevent setting an Expense to Reimbursed status unless its current status is Fully_Approved.
4. THE Accountant SHALL view a list of all Fully_Approved Expenses pending reimbursement, filterable by Department, Employee, and Expense_Category.
5. THE Accountant SHALL view a list of all Reimbursed Expenses with their payment reference and payment date, filterable by date range, Department, and Employee.

---

### Requirement 12: Expense Lifecycle Status Model

**User Story:** As any authenticated user, I want expense statuses to follow a defined lifecycle, so that the current state of every expense is unambiguous.

#### Acceptance Criteria

1. THE BEMS SHALL enforce that an Expense transitions only through the following allowed status paths:
   - Draft → Submitted
   - Submitted → Manager_Approved | Rejected
   - Manager_Approved → Accountant_Approved | Rejected | Fully_Approved (when amount < ₹5,000 is not applicable; fully approved directly after Manager for < ₹5,000)
   - Accountant_Approved → Fully_Approved | Rejected
   - Fully_Approved → Reimbursed
   - Rejected → Submitted (via resubmission)
2. THE BEMS SHALL set status to Fully_Approved immediately after Manager_Approved when the expense amount is less than ₹5,000 and no further approvers are required in the chain.
3. THE BEMS SHALL set status to Fully_Approved immediately after Accountant_Approved when the expense amount is between ₹5,000 and ₹50,000 inclusive.
4. IF an Expense transitions are attempted that are not in the allowed paths in Acceptance Criterion 1, THEN THE BEMS SHALL return an HTTP 400 response with an error message describing the invalid transition.

---

### Requirement 13: Notification System

**User Story:** As any authenticated user, I want to receive timely in-app and email notifications for actions that require my attention, so that I can act promptly.

#### Acceptance Criteria

1. WHEN an Expense is submitted, THE BEMS SHALL send an in-app and email notification to the assigned first approver in the Approval_Chain.
2. WHEN an Expense is approved by any approver, THE BEMS SHALL send an in-app and email notification to the next approver in the Approval_Chain, or to the owning Employee if the Expense has reached Fully_Approved status.
3. WHEN an Expense is rejected by any approver, THE BEMS SHALL send an in-app and email notification to the owning Employee containing the approver's rejection reason.
4. WHEN an Expense is marked as Reimbursed, THE BEMS SHALL send an in-app and email notification to the owning Employee.
5. WHEN an Expense remains in Submitted status awaiting a Manager's action for more than 5 business days and no Delegate_Approver is active, THE BEMS SHALL send an in-app and email notification to the Admin and Business_Owner.
6. THE Employee SHALL view all their in-app notifications in a dedicated notification centre, with unread notifications visually distinguished from read notifications.
7. WHEN a user reads a notification, THE BEMS SHALL mark it as read and record the read timestamp.

---

### Requirement 14: Expense Reporting and Analytics

**User Story:** As an authorised user, I want to view and export expense reports, so that I can make data-driven decisions and maintain financial oversight.

#### Acceptance Criteria

1. THE Employee SHALL view a real-time report of their own Expenses filterable by date range, Expense_Category, and status.
2. THE Manager SHALL view a real-time report of all Expenses submitted by Employees in their Department, filterable by date range, Expense_Category, Employee, and status.
3. THE Accountant SHALL view a real-time financial report of all Expenses across all Departments within the Business, filterable by date range, Department, Expense_Category, and status.
4. THE Admin SHALL view a real-time business-level report of all Expenses (requiring explicit all-expenses access permission), department budgets, and category utilisation, filterable by date range and Department.
5. THE Business_Owner SHALL view all reports available to the Manager, Accountant, and Admin roles within the same Business.
6. WHEN a user generates a report, THE BEMS SHALL render the report data in real time from the current database state without caching stale data.
7. THE BEMS SHALL allow any authorised user to export a report to PDF format and to Excel (XLSX) format.
8. WHEN an export is requested, THE BEMS SHALL generate the file and initiate a download within 10 seconds for reports containing up to 10,000 records.

---

### Requirement 15: Dashboard and Summary Views

**User Story:** As any authenticated user, I want a role-specific dashboard, so that I can see key metrics relevant to my responsibilities immediately after login.

#### Acceptance Criteria

1. WHEN an Employee authenticates, THE BEMS SHALL display a dashboard showing: total expenses this month, pending expenses, approved expenses, reimbursed expenses, and remaining monthly budget.
2. WHEN a Manager authenticates, THE BEMS SHALL display a dashboard showing: department expenses this month, expenses pending their approval, and department budget utilisation percentage.
3. WHEN an Accountant authenticates, THE BEMS SHALL display a dashboard showing: total expenses awaiting verification, total reimbursements processed this month, and total reimbursement value.
4. WHEN an Admin authenticates, THE BEMS SHALL display a dashboard showing: total active users, total departments, expenses submitted this month, and any policy limit breaches in the current month.
5. WHEN a Business_Owner authenticates, THE BEMS SHALL display a dashboard showing all metrics from criteria 2, 3, and 4 plus overall business spend versus total budget for the current month.

---

### Requirement 16: Authentication and Session Management

**User Story:** As a registered user, I want to securely authenticate and manage my session, so that my account and business data are protected.

#### Acceptance Criteria

1. THE BEMS SHALL authenticate users via email and password; WHEN credentials are valid, THE BEMS SHALL issue a JWT access token with a 15-minute expiry and a refresh token with a 7-day expiry.
2. WHEN a user's access token expires, THE BEMS SHALL allow the client to obtain a new access token using a valid refresh token without requiring the user to re-enter credentials.
3. IF a user provides invalid credentials 5 consecutive times, THEN THE BEMS SHALL lock the account for 15 minutes and send an in-app and email notification to the user.
4. THE BEMS SHALL allow a user to log out; WHEN a user logs out, THE BEMS SHALL invalidate the current refresh token immediately.
5. THE BEMS SHALL allow a user to reset their password via a time-limited (1-hour) link sent to their registered email address.
6. WHEN a new user activates their account via the activation link, THE BEMS SHALL require the user to set a password that meets ALL of the following criteria simultaneously: minimum 8 characters, at least one uppercase letter, at least one lowercase letter, at least one digit, and at least one special character; IF any single criterion is not met, THE BEMS SHALL reject the password and indicate which criterion failed.
7. THE BEMS SHALL store all passwords as bcrypt hashes; THE BEMS SHALL never persist or log plaintext passwords; temporary in-memory retention of a plaintext password during the hashing process within a single request lifecycle is permitted provided the value is not stored to any persistent medium.

---

### Requirement 17: Role-Based Access Control

**User Story:** As a system designer, I want all API endpoints to enforce role-based access control, so that users can only perform actions permitted by their assigned role.

#### Acceptance Criteria

1. THE BEMS SHALL enforce the following permission boundaries:
   - Employee: submit, edit, delete own Draft/Rejected expenses; view own expenses and reports; view own notifications.
   - Manager: all Employee permissions; approve or reject Submitted expenses in own Department; manage Delegate_Approver; view Department reports.
   - Accountant: approve or reject Manager_Approved expenses; mark Fully_Approved expenses as Reimbursed; view all business financial reports.
   - Admin: manage users, departments, categories, and policies; view business reports; cannot approve any expense.
   - Business_Owner: all permissions of all roles within the Business; override any pending approval.
   - Platform_Super_Admin: manage Business tenants; no access to business-level expense data.
2. IF any authenticated request does not satisfy the role permission check for the targeted resource, THEN THE BEMS SHALL deny access, return HTTP 403, and log the unauthorised access attempt in the Audit_Log.
3. THE BEMS SHALL perform role permission checks on every API request and deny access when checks fail, without relying on cached role data that is older than the current request.

---

### Requirement 18: Audit Trail

**User Story:** As a Business_Owner or Accountant, I want a complete audit trail for every action in the system, so that I can investigate discrepancies and demonstrate compliance.

#### Acceptance Criteria

1. THE BEMS SHALL record an Audit_Log entry for every create, update, status-change, login, logout, and Soft_Delete action performed on any entity.
2. EACH Audit_Log entry SHALL contain: entity type, entity ID, action type, actor user ID, actor role, timestamp (UTC), the previous state (JSON), and the new state (JSON).
3. THE BEMS SHALL prevent modification or deletion of any Audit_Log entry; Audit_Log entries are append-only.
4. THE Business_Owner SHALL view and filter the Audit_Log by date range, actor user, entity type, and action type.
5. THE Accountant SHALL view Audit_Log entries scoped to Expense and Reimbursement entities.
6. THE BEMS SHALL retain all Audit_Log entries for a minimum of 7 years from the date of creation.

---

### Requirement 19: Data Retention and Soft Delete

**User Story:** As a Platform_Super_Admin, I want all deletions to be non-destructive and records retained for 7 years, so that the platform meets long-term data retention requirements.

#### Acceptance Criteria

1. THE BEMS SHALL apply Soft_Delete to all deletions of Business, Department, User, Expense, Expense_Category, and Attachment records by setting a `deleted_at` timestamp and a `deleted_by` user ID.
2. THE BEMS SHALL exclude Soft_Deleted records from all standard query results unless the requestor holds the Business_Owner or Platform_Super_Admin role and explicitly requests deleted records.
3. THE BEMS SHALL retain all records, including Soft_Deleted records, for a minimum of 7 years from their creation date.
4. WHEN a record's retention period of 7 years has elapsed, THE BEMS SHALL flag the record for a scheduled purge; THE Platform_Super_Admin SHALL provide explicit confirmation before permanent deletion is executed, and THE BEMS SHALL reject any permanent deletion request that has not been explicitly confirmed by the Platform_Super_Admin.

---

### Requirement 20: API Design Standards

**User Story:** As a backend developer, I want all APIs to follow consistent RESTful standards, so that the frontend and third-party integrations can be built reliably.

#### Acceptance Criteria

1. THE BEMS SHALL expose all business functionality through RESTful HTTP APIs using JSON as the data exchange format.
2. THE BEMS SHALL version all API endpoints under a `/api/v1/` path prefix.
3. WHEN an API request fails due to a client error, THE BEMS SHALL return a JSON response body containing: `status`, `errorCode`, `message`, and `timestamp` fields.
4. WHEN an API request succeeds, THE BEMS SHALL return a JSON response body containing: `status`, `data`, and `timestamp` fields.
5. THE BEMS SHALL support pagination for all list endpoints using `page`, `size`, and `sortBy` query parameters, with a default page size of 20 and a maximum page size of 100.
6. THE BEMS SHALL document all API endpoints using OpenAPI 3.0 (Swagger), accessible at `/api/v1/docs`.

---

### Requirement 21: Performance and Scalability

**User Story:** As a Platform_Super_Admin, I want the system to perform reliably under concurrent usage, so that all users experience acceptable response times.

#### Acceptance Criteria

1. WHEN any authenticated API request is received under normal load (up to 200 concurrent users), THE BEMS SHALL respond within 500 milliseconds at the 95th percentile.
2. WHEN a report export for up to 10,000 records is requested, THE BEMS SHALL deliver the generated file within 10 seconds.
3. THE BEMS SHALL support horizontal scaling; no user session state shall be stored in application memory (stateless JWT authentication).
4. THE BEMS SHALL use database connection pooling with a minimum pool size of 5 and a maximum pool size of 50 connections.

---

### Requirement 22: Security Standards

**User Story:** As a Platform_Super_Admin, I want the system to enforce security best practices, so that business and personal data are protected against common vulnerabilities.

#### Acceptance Criteria

1. THE BEMS SHALL enforce HTTPS for all API communication; HTTP requests SHALL be redirected to HTTPS.
2. THE BEMS SHALL sanitise all user-supplied input before persisting to the database to prevent SQL injection and XSS attacks.
3. THE BEMS SHALL implement CORS policies that restrict API access to approved client origins only.
4. THE BEMS SHALL include rate limiting on authentication endpoints: no more than 10 login attempts per IP address per minute.
5. THE BEMS SHALL validate uploaded Attachment files by inspecting both the file's MIME type and magic bytes; IF the detected file type does not match the declared file extension, THEN THE BEMS SHALL reject the upload and return a descriptive error message.
6. THE BEMS SHALL not include sensitive fields (passwords, JWT secrets, database credentials) in API responses or application logs.

---

### Requirement 23: Error Handling and Resilience

**User Story:** As any user, I want the system to handle errors gracefully and provide meaningful feedback, so that I understand what went wrong and how to resolve it.

#### Acceptance Criteria

1. WHEN an unhandled exception occurs, THE BEMS SHALL return an HTTP 500 response with a generic error message and a unique correlation ID; THE BEMS SHALL log the full exception details server-side using the correlation ID.
2. WHEN a requested resource is not found, THE BEMS SHALL return an HTTP 404 response with a message identifying the resource type and the requested ID.
3. WHEN a request body fails validation, THE BEMS SHALL return an HTTP 400 response listing each invalid field and the reason for the validation failure.
4. THE BEMS SHALL implement a global exception handler that intercepts all unhandled exceptions and maps them to the standard error response format defined in Requirement 20, Acceptance Criterion 3.

---

### Requirement 24: Future GDPR Compatibility

**User Story:** As a Platform_Super_Admin, I want the system architecture to support future GDPR compliance without a full redesign, so that the platform can expand to European markets.

#### Acceptance Criteria

1. THE BEMS SHALL store personally identifiable information (full name, email address) in dedicated columns that can be independently encrypted or pseudonymised without altering the schema of other columns.
2. THE BEMS SHALL include a `data_subject_id` foreign key on the User entity to support future linkage to a GDPR data-subject request management service.
3. THE BEMS SHALL log all access to PII fields (full name, email) in the Audit_Log with the accessing user ID and timestamp to support future GDPR access-log requirements.
