# Implementation Plan: Business Expense Management System (BEMS)

## Overview

Implement a multi-tenant, production-quality expense management platform using a Maven multi-module project (`bems-parent`, `bems-domain`, `bems-application`, `bems-integration-test`) following Hexagonal (Ports & Adapters) architecture. The domain layer is pure Java with no framework dependencies; Spring Boot wires adapters in `bems-application`. All 23 correctness properties are verified with jqwik property-based tests; end-to-end flows are verified with Testcontainers integration tests.

---

## Tasks

- [ ] 1. Project Scaffolding and Maven Multi-Module Setup
  - [ ] 1.1 Create the Maven multi-module parent POM and three child modules (`bems-domain`, `bems-application`, `bems-integration-test`)
    - Define `bems-parent/pom.xml` with `<modules>`, dependency management for Java 21, Spring Boot 3.3 BOM, jqwik 1.8, Testcontainers, JJWT 0.12, Apache POI, OpenPDF, Flyway, HikariCP, springdoc-openapi 2, Mockito, JUnit 5
    - Create `bems-domain/pom.xml` — pure Java, no Spring, no JPA dependencies
    - Create `bems-application/pom.xml` — depends on `bems-domain`; includes Spring Boot Starter Web, Security, Data JPA, Mail, Validation, Actuator, springdoc, Flyway, PostgreSQL driver
    - Create `bems-integration-test/pom.xml` — depends on `bems-application`; includes Testcontainers PostgreSQL, REST Assured
    - Set up `src/main/java` and `src/test/java` directory trees for all modules following the `com.bems` package structure defined in the design
    - Create `bems-application/src/main/resources/application.yml` with HikariCP pool (min=5, max=50), Flyway enabled, JWT secret placeholder, async thread-pool config, storage mode config
    - _Requirements: 20.1, 21.3, 21.4_

- [ ] 2. Domain Model — Enums, Value Objects, and Core Entities
  - [ ] 2.1 Implement domain enums and value objects in `bems-domain`
    - Create `ExpenseStatus` enum: `DRAFT, SUBMITTED, MANAGER_APPROVED, ACCOUNTANT_APPROVED, FULLY_APPROVED, REIMBURSED, REJECTED`
    - Create `ApprovalTier` enum: `MANAGER_ONLY, MANAGER_ACCOUNTANT, MANAGER_ACCOUNTANT_OWNER`
    - Create `UserRole` enum: `PLATFORM_SUPER_ADMIN, BUSINESS_OWNER, ADMIN, MANAGER, ACCOUNTANT, EMPLOYEE`
    - Create `UserStatus` enum: `ACTIVE, INACTIVE, LOCKED`
    - Create `BusinessStatus` enum: `ACTIVE, INACTIVE`
    - Create `ActionType` enum: `CREATE, UPDATE, STATUS_CHANGE, LOGIN, LOGOUT, SOFT_DELETE`
    - Create `PolicyType` enum: `EMPLOYEE_MONTHLY, DEPARTMENT_MONTHLY`
    - Create `Money` value object wrapping `BigDecimal` amount; enforce positive, two-decimal precision
    - Create `TenantId`, `UserId`, `ExpenseId`, `DepartmentId`, `CategoryId` typed UUID wrappers
    - _Requirements: 6.1, 12.1, 2.1, 16.1_
  - [ ] 2.2 Implement core domain entity classes in `bems-domain`
    - Create `Business` entity: id, name, country, currencyCode, contactEmail, status, createdAt, deletedAt, deletedBy
    - Create `User` entity: id, businessId, departmentId, fullName, email, passwordHash, role, status, failedLoginCount, lockedUntil, activationToken, activationExpiresAt, dataSubjectId, deletedAt, deletedBy
    - Create `Department` entity: id, businessId, name, description, status, deletedAt, deletedBy
    - Create `ExpenseCategory` entity: id, businessId, name, description, perTransactionLimit (nullable), status, deletedAt, deletedBy
    - Create `SpendingPolicy` entity: id, businessId, policyType, targetId, monthlyLimit
    - Create `Expense` entity: all columns from data model; include `resubmissionCount`, `lastResubmittedAt`, `isEscalated`, `approvalTier`, `currentApproverId`
    - Create `ApprovalStep` value object (append-only): expenseId, approverId, approverRole, action, comment, actedAt, stepOrder
    - Create `Attachment` entity: id, expenseId, businessId, originalFilename, mimeType, fileSizeBytes, storageKey, uploadedBy, uploadedAt, deletedAt, deletedBy
    - Create `DelegateApprover` entity: id, businessId, managerId, delegateId, startDate, endDate, isActive
    - Create `Notification` entity: id, businessId, recipientId, type, title, body, referenceEntityType, referenceEntityId, isRead, readAt, createdAt
    - Create `AuditLog` entity: all columns from data model; JSONB fields represented as `String` in domain
    - Create `RefreshToken` entity: id, userId, tokenHash, expiresAt, revokedAt, createdAt
    - Create `BusinessConfig` entity: id, businessId, escalationThresholdDays
    - _Requirements: 1.1, 2.1, 3.1, 4.1, 5.1, 6.1, 8.5, 11.1, 13.1, 18.2_

- [ ] 3. Expense State Machine
  - [ ] 3.1 Implement `ExpenseStateMachine` in `bems-domain/statemachine`
    - Define the static `TRANSITIONS` map covering all 10 allowed transition pairs from the design
    - Implement `transition(ExpenseStatus from, ExpenseStatus to)`: returns `to` if allowed, throws `InvalidStateTransitionException` otherwise
    - Implement approval-tier shortcut logic: after Manager approval on `MANAGER_ONLY` tier → emit `FULLY_APPROVED`; after Accountant approval on `MANAGER_ACCOUNTANT` tier → emit `FULLY_APPROVED`
    - Create `InvalidStateTransitionException` extending `BemsException`
    - _Requirements: 12.1, 12.2, 12.3, 12.4_
  - [ ]* 3.2 Write property test for expense state machine transition validity (Property 8)
    - **Property 8: Expense State Machine Transition Validity**
    - **Validates: Requirements 12.1, 12.4**
    - Generate all pairs `(S, T)` from `ExpenseStatus × ExpenseStatus`; assert exactly the 10 allowed pairs succeed and all others throw `InvalidStateTransitionException`
  - [ ]* 3.3 Write unit tests for `ExpenseStateMachine`
    - Verify every valid transition succeeds; verify every invalid transition throws; verify shortcut logic for MANAGER_ONLY and MANAGER_ACCOUNTANT tiers
    - _Requirements: 12.1, 12.2, 12.3_

- [ ] 4. Approval Chain Strategy Pattern
  - [ ] 4.1 Implement `ApprovalChainStrategy` interface and three concrete strategies in `bems-domain`
    - `ManagerOnlyStrategy`: `supports(amount < 5000)`, `chain() = [MANAGER]`
    - `ManagerAccountantStrategy`: `supports(5000 ≤ amount ≤ 50000)`, `chain() = [MANAGER, ACCOUNTANT]`
    - `FullChainStrategy`: `supports(amount > 50000)`, `chain() = [MANAGER, ACCOUNTANT, BUSINESS_OWNER]`
    - Implement `ApprovalChainResolver` that iterates strategies and returns the first match
    - _Requirements: 6.5, 12.1, 12.2, 12.3_
  - [ ]* 4.2 Write property test for approval tier determination by amount (Property 7)
    - **Property 7: Approval Tier Determination by Amount**
    - **Validates: Requirements 6.5, 12.1, 12.2, 12.3**
    - Use `@BigRange` generators for each of the three amount partitions; assert correct tier for 1000 iterations
  - [ ]* 4.3 Write unit tests for `ApprovalChainResolver`
    - Boundary values: 4999.99, 5000.00, 50000.00, 50000.01
    - _Requirements: 6.5_

- [ ] 5. Spending Policy Domain Validators
  - [ ] 5.1 Implement `SpendingPolicyChain` with three validators in `bems-domain/policy`
    - Create `SpendingPolicyViolationException` with fields: `applicableLimit`, `currentTotal`, `overage`
    - `CategoryLimitValidator`: checks `expense.amount ≤ category.perTransactionLimit` (skip if null)
    - `EmployeeMonthlyLimitValidator`: checks `sumPendingAndApproved(employeeId, month) + newAmount ≤ employeePolicy.monthlyLimit` (skip if no policy)
    - `DepartmentMonthlyLimitValidator`: checks `sumPendingAndApproved(departmentId, month) + newAmount ≤ deptPolicy.monthlyLimit` (skip if no policy)
    - Chain executes all three in order; throws on first violation encountered
    - _Requirements: 5.3, 5.4, 5.5, 4.2_
  - [ ]* 5.2 Write property test for employee monthly spending limit enforcement (Property 9)
    - **Property 9: Employee Monthly Spending Limit Enforcement**
    - **Validates: Requirements 5.3, 5.5**
    - Generate random limit L, existing sum S, new amount A; assert rejection iff S + A > L; assert error detail completeness
  - [ ]* 5.3 Write property test for department monthly budget enforcement (Property 10)
    - **Property 10: Department Monthly Budget Enforcement**
    - **Validates: Requirements 5.4, 5.5**
    - Same structure as Property 9 but for department dimension
  - [ ]* 5.4 Write property test for spending policy violation error detail completeness (Property 23)
    - **Property 23: Spending Policy Violation Error Detail Completeness**
    - **Validates: Requirements 5.5**
    - Assert overage field = (currentTotal + submittedAmount) − applicableLimit for all rejection cases
  - [ ]* 5.5 Write unit tests for `SpendingPolicyChain`
    - At-limit (exactly equal), one cent over, zero existing expenses, null policy (no limit), all three validators triggered
    - _Requirements: 5.3, 5.4, 5.5_

- [ ] 6. Domain Ports (Repository and Service Interfaces)
  - [ ] 6.1 Define all domain port interfaces in `bems-domain/port`
    - `BusinessRepository`: `save`, `findById`, `findAll(Pageable)`, `findByIdAndStatus`
    - `UserRepository`: `save`, `findById`, `findByEmail`, `findByBusinessId`, `countActiveByRoleAndBusinessId`
    - `DepartmentRepository`: `save`, `findById`, `findByBusinessId`, `countActiveEmployees(departmentId)`
    - `ExpenseCategoryRepository`: `save`, `findById`, `findByBusinessId`
    - `SpendingPolicyRepository`: `save`, `findByTypeAndTargetId`, `sumPendingAndApprovedForEmployee`, `sumPendingAndApprovedForDepartment`
    - `ExpenseRepository`: `save`, `findById`, `findPendingForApprover`, `findBySubmitter`, `findByDepartment`, `findByBusiness`, `findFullyApproved`, `findEscalationCandidates(thresholdDays)`
    - `ApprovalStepRepository`: `save`, `findByExpenseId`
    - `AttachmentRepository`: `save`, `findByExpenseId`, `softDelete`
    - `DelegateApproverRepository`: `save`, `findActiveDelegate(managerId, today)`, `findExpiredDelegations(today)`
    - `NotificationRepository`: `save`, `findByRecipient(pageable)`, `markAsRead`
    - `AuditLogRepository`: `insert(AuditLog)`, `findByFilter`
    - `RefreshTokenRepository`: `save`, `findByTokenHash`, `revoke`
    - `BusinessConfigRepository`: `findByBusinessId`, `save`
    - `AttachmentStoragePort`: `store`, `retrieve`, `delete`
    - `NotificationPort`: `sendInApp`, `sendEmail`
    - _Requirements: 1.1, 2.1, 3.1, 4.1, 5.1, 6.1, 8.5, 11.1, 13.1, 18.1_

- [ ] 7. Domain Service Utilities
  - [ ] 7.1 Implement `AttachmentValidator` in `bems-domain`
    - Implement magic-bytes detection map: PDF (`%PDF`), JPEG (`FF D8 FF`), PNG (`89 50 4E 47`)
    - Validate declared MIME type matches detected magic bytes; reject on mismatch
    - Validate file size ≤ 10,485,760 bytes (10 MiB)
    - Throw `InvalidAttachmentException` with descriptive message on any failure
    - _Requirements: 6.3, 22.5_
  - [ ]* 7.2 Write property test for attachment validation MIME + magic bytes (Property 11)
    - **Property 11: Attachment Validation (MIME + Magic Bytes)**
    - **Validates: Requirements 6.3, 22.5**
    - Generate files with all combinations of valid/invalid magic bytes and sizes; assert accepted iff both conditions hold
  - [ ] 7.3 Implement `ExpenseDateValidator` in `bems-domain`
    - Reject submission if `submissionDate − expenseDate > 30 calendar days`
    - Throw `ExpenseDatePolicyException` with policy message
    - _Requirements: 6.7_
  - [ ]* 7.4 Write property test for 30-day expense date recency rule (Property 12)
    - **Property 12: 30-Day Expense Date Recency Rule**
    - **Validates: Requirements 6.7**
    - Generate arbitrary `(expenseDate, submissionDate)` pairs; assert rejection iff gap > 30 days
  - [ ] 7.5 Implement `PasswordPolicyValidator` in `bems-domain`
    - Check all five criteria simultaneously (no short-circuit): length ≥ 8, uppercase, lowercase, digit, special character
    - Return `ValidationResult` with set of all failed criteria (not just the first)
    - _Requirements: 16.6_
  - [ ]* 7.6 Write property test for password policy all-criteria simultaneous enforcement (Property 16)
    - **Property 16: Password Policy — All-Criteria Simultaneous Enforcement**
    - **Validates: Requirements 16.6**
    - Generate arbitrary password strings; assert accepted iff all 5 criteria hold; assert rejection lists all k failed criteria
  - [ ] 7.7 Implement `DelegateApproverEvaluator` in `bems-domain`
    - Evaluate delegation active status using UTC only: `date(T, UTC) >= startDate AND date(T, UTC) <= endDate`
    - Accept injected `Instant` clock for testability
    - _Requirements: 8.6_
  - [ ]* 7.8 Write property test for delegate approver UTC date-range strictness (Property 13)
    - **Property 13: Delegate Approver UTC Date-Range Strictness**
    - **Validates: Requirements 8.6**
    - Generate arbitrary `(startDate, endDate, timestamp)` triples; assert active iff UTC date within range

- [ ] 8. Checkpoint — Domain Layer Tests Pass
  - Ensure all domain unit tests and property tests pass with no Spring context; run `mvn test -pl bems-domain`; ask the user if questions arise.

- [ ] 9. Flyway Database Migrations
  - [ ] 9.1 Create Flyway migration `V1__create_businesses.sql`
    - `businesses` table with all columns, PK, UNIQUE constraint on `name`, index on `status`
    - _Requirements: 1.1, 19.1_
  - [ ] 9.2 Create Flyway migration `V2__create_users.sql`
    - `users` table; UNIQUE index `(business_id, email) WHERE deleted_at IS NULL`; index `(business_id, role)`
    - _Requirements: 2.1, 19.1_
  - [ ] 9.3 Create Flyway migration `V3__create_departments_and_categories.sql`
    - `departments` table; `expense_categories` table; UNIQUE `(business_id, name)` on each
    - _Requirements: 3.1, 4.1_
  - [ ] 9.4 Create Flyway migration `V4__create_spending_policies.sql`
    - `spending_policies` table; UNIQUE constraint `(business_id, policy_type, target_id)`
    - _Requirements: 5.1_
  - [ ] 9.5 Create Flyway migration `V5__create_expenses_and_approval_steps.sql`
    - `expenses` table with all columns and indexes: `(business_id, status)`, `(business_id, submitter_id)`, `(business_id, department_id, status)`, `(current_approver_id) PARTIAL WHERE deleted_at IS NULL`
    - `approval_steps` table (no UPDATE/DELETE)
    - _Requirements: 6.1, 8.1, 12.1_
  - [ ] 9.6 Create Flyway migration `V6__create_attachments_and_delegates.sql`
    - `attachments` table; `delegate_approvers` table with constraint `start_date <= end_date`
    - _Requirements: 6.2, 8.5_
  - [ ] 9.7 Create Flyway migration `V7__create_notifications_and_audit.sql`
    - `notifications` table; index `(recipient_id, is_read) WHERE deleted_at IS NULL`
    - `audit_log` table (no UPDATE/DELETE); partition by `timestamp` for 7-year retention
    - _Requirements: 13.1, 18.1, 18.3, 18.6_
  - [ ] 9.8 Create Flyway migration `V8__create_auth_and_config_tables.sql`
    - `refresh_tokens` table; `business_configs` table; `notification_failures` table (id, notification_id, error_message, retry_count, next_retry_at, created_at)
    - _Requirements: 16.1, 8.1, 11.2_

- [ ] 10. JPA Persistence Adapters
  - [ ] 10.1 Create JPA entity classes in `bems-application/adapter/persistence/entity`
    - Annotate each entity with `@Entity`, `@Table`, `@FilterDef` and `@Filter` for `tenantFilter` (condition: `business_id = :businessId`) on all business-scoped entities
    - Add `@EntityListeners(AuditingEntityListener.class)` for `createdAt`/`updatedAt` auto-population
    - Map JSONB columns (`previous_state`, `new_state`) with custom `JsonbType` Hibernate type
    - _Requirements: 1.5, 19.1_
  - [ ] 10.2 Create Spring Data JPA repository interfaces in `bems-application/adapter/persistence`
    - Implement all methods declared in domain port interfaces using Spring Data query methods or `@Query`
    - Add `@Modifying` native query for audit log INSERT (bypass JPA merge path)
    - Add `@Query` for `sumPendingAndApprovedForEmployee` and `sumPendingAndApprovedForDepartment`
    - Implement `TenantAwareRepository` base class that activates the Hibernate `tenantFilter`
    - _Requirements: 5.3, 5.4, 18.3_
  - [ ] 10.3 Implement `TenantContext` thread-local and `TenantContextHolder` in `bems-application`
    - Store and clear `businessId` (UUID) per request thread
    - _Requirements: 1.5_
  - [ ] 10.4 Create entity-to-domain mappers (using MapStruct or manual) in `adapter/persistence`
    - `BusinessMapper`, `UserMapper`, `DepartmentMapper`, `ExpenseCategoryMapper`, `SpendingPolicyMapper`
    - `ExpenseMapper`, `ApprovalStepMapper`, `AttachmentMapper`, `DelegateApproverMapper`
    - `NotificationMapper`, `AuditLogMapper`, `RefreshTokenMapper`, `BusinessConfigMapper`
    - _Requirements: 20.1_
  - [ ]* 10.5 Write `@DataJpaTest` repository slice tests
    - Verify tenant filter excludes cross-business data; verify soft-delete exclusion; verify audit log INSERT-only behavior
    - _Requirements: 1.5, 18.3, 19.2_

- [ ] 11. Security Infrastructure
  - [ ] 11.1 Implement JWT utilities in `bems-application/adapter/web/security`
    - `JwtTokenProvider`: generate access JWT (sub, bid, role, iat, exp=+15min) and refresh token (opaque UUID, stored as SHA-256 hash, exp=+7days) using JJWT 0.12
    - `JwtAuthenticationFilter`: extract Bearer token from `Authorization` header; validate signature and expiry; load `UserDetails`; set `SecurityContext`
    - `TenantContextFilter`: extract `bid` claim; populate `TenantContextHolder`; clear after response
    - _Requirements: 16.1, 16.2, 21.3_
  - [ ]* 11.2 Write property test for JWT token expiry invariants (Property 15)
    - **Property 15: JWT Token Expiry Invariants**
    - **Validates: Requirements 16.1**
    - Generate arbitrary issuance timestamps; assert access token exp = iat + 900s (±5s); assert refresh exp = iat + 604800s (±5s)
  - [ ] 11.3 Implement `RateLimitingFilter` in `bems-application`
    - Track login attempts per IP in `ConcurrentHashMap<String, AtomicInteger>`
    - Return HTTP 429 after 10 attempts per IP per minute
    - Add `@Scheduled` cleanup every 60 seconds to evict expired entries
    - _Requirements: 22.4_
  - [ ] 11.4 Implement account lockout logic in `UserService`
    - Increment `failed_login_count` on each failed login attempt
    - Set `locked_until = now + 15 minutes` and `status = LOCKED` after 5 consecutive failures
    - Send lockout notification to locked user
    - Clear `failed_login_count` on successful login
    - _Requirements: 16.3_
  - [ ] 11.5 Configure Spring Security filter chain in `SecurityConfig`
    - Register `CorsFilter`, `JwtAuthenticationFilter`, `TenantContextFilter` in correct order
    - Enable `@EnableMethodSecurity` for `@PreAuthorize` support
    - Configure `HttpSecurity`: stateless session, CSRF disabled for REST, HTTPS enforcement
    - Register `TenantGuard` and `ExpenseGuard` as Spring beans for `@PreAuthorize` SpEL
    - _Requirements: 17.1, 17.2, 17.3, 22.1, 22.3_
  - [ ]* 11.6 Write security tests for JWT filter chain, RBAC, and rate limiting
    - Test valid/expired/malformed tokens; test role-based access returns 403 on violation; test IP rate limit returns 429
    - _Requirements: 17.2, 22.4_

- [ ] 12. Global Error Handling and API Standards
  - [ ] 12.1 Implement `GlobalExceptionHandler` in `adapter/web`
    - Map `InvalidStateTransitionException` → 400 with `INVALID_STATE_TRANSITION` errorCode
    - Map `SpendingPolicyViolationException` → 422 with limit/total/overage detail fields
    - Map `InvalidAttachmentException` → 400
    - Map `ResourceNotFoundException` → 404 with entity type and ID
    - Map `AccessDeniedException` → 403 and log attempt in audit log
    - Map `AccountLockedException` → 423
    - Map `ConstraintViolationException` / `MethodArgumentNotValidException` → 400 with `fieldErrors` array
    - Catch-all `Throwable` → 500 with `correlationId` (UUID), log full exception server-side
    - _Requirements: 17.2, 20.3, 23.1, 23.2, 23.3, 23.4_
  - [ ] 12.2 Implement standard response wrappers `ApiResponse<T>` and `ErrorResponse`
    - `ApiResponse`: `status`, `data`, `timestamp` fields
    - `ErrorResponse`: `status`, `errorCode`, `message`, `timestamp`, `correlationId`, optional `fieldErrors`
    - Add `CorrelationIdFilter` to generate and inject `X-Correlation-Id` into MDC per request
    - _Requirements: 20.3, 20.4, 23.1_
  - [ ]* 12.3 Write property test for standard API error response structure (Property 22)
    - **Property 22: Standard API Error Response Structure**
    - **Validates: Requirements 20.3, 23.3, 23.4**
    - Generate arbitrary 4xx error scenarios; assert all four required fields present and non-null in every response

- [ ] 13. Authentication Use Cases and Controller
  - [ ] 13.1 Implement `AuthenticationUseCase` in `bems-application/usecase`
    - `login(email, password, ipAddress)`: verify business active (Req 1.4), verify user active and not locked, verify BCrypt hash, check lockout; issue JWT pair; reset `failed_login_count`; publish `UserLoggedInEvent` for audit
    - `refreshToken(rawRefreshToken)`: load by SHA-256 hash, verify not revoked and not expired, issue new access JWT, rotate refresh token (revoke old, issue new)
    - `logout(userId, rawRefreshToken)`: revoke refresh token; publish `UserLoggedOutEvent`
    - `requestPasswordReset(email)`: generate 1-hour reset token, persist, send email
    - `resetPassword(token, newPassword)`: validate token not expired, validate password policy (all 5 criteria), hash with BCrypt, save
    - `activateAccount(token, password)`: validate 48h activation token, enforce password policy, hash and save, mark user active; clear activation token
    - _Requirements: 16.1, 16.2, 16.3, 16.4, 16.5, 16.6, 16.7, 1.4_
  - [ ] 13.2 Implement `AuthController` at `/api/v1/auth`
    - `POST /login` → `AuthenticationUseCase.login`; return access token in body, refresh token as `HttpOnly` cookie
    - `POST /refresh` → `AuthenticationUseCase.refreshToken`
    - `POST /logout` → `AuthenticationUseCase.logout`
    - `POST /password-reset/request` → `AuthenticationUseCase.requestPasswordReset`
    - `POST /password-reset/confirm` → `AuthenticationUseCase.resetPassword`
    - `POST /activate` → `AuthenticationUseCase.activateAccount`
    - _Requirements: 16.1, 16.2, 16.4, 16.5, 16.6_
  - [ ]* 13.3 Write unit tests for `AuthenticationUseCase`
    - Valid login, wrong password, 5th consecutive failure triggers lockout, locked business blocks login, token refresh rotation, logout revokes token, password reset 1-hour expiry
    - _Requirements: 16.1, 16.3, 16.4, 16.5, 1.4_

- [ ] 14. Business Tenant Management
  - [ ] 14.1 Implement `BusinessManagementUseCase` in `usecase`
    - `createBusiness(command)`: validate unique name; generate UUID; set status `ACTIVE`; publish `BusinessCreatedEvent`
    - `deactivateBusiness(businessId)`: set status `INACTIVE`; publish event (blocks all tenant auth)
    - `reactivateBusiness(businessId)`: set status `ACTIVE`
    - `listBusinesses(pageable)`: paginated list with name, status, createdAt
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.6_
  - [ ] 14.2 Implement `BusinessController` at `/api/v1/businesses` with `@PreAuthorize("hasRole('PLATFORM_SUPER_ADMIN')")`
    - `POST /` → createBusiness; `PATCH /{id}/deactivate`; `PATCH /{id}/reactivate`; `GET /` paginated
    - _Requirements: 1.1, 1.3, 1.6, 17.1_
  - [ ]* 14.3 Write property test for business creation invariants (Property 1)
    - **Property 1: Business Creation Invariants**
    - **Validates: Requirements 1.2**
    - Generate valid business inputs; assert resulting business has non-null UUID and status = ACTIVE
  - [ ]* 14.4 Write property test for business deactivation blocks all tenant users (Property 3)
    - **Property 3: Business Deactivation Blocks All Tenant Users**
    - **Validates: Requirements 1.4**
    - Deactivate business; attempt login for each user belonging to that business; assert all rejected until reactivation

- [ ] 15. User Management Use Cases and Controller
  - [ ] 15.1 Implement `UserManagementUseCase` in `usecase`
    - `createUser(command)`: validate one-active-Business_Owner invariant (Req 2.7); assign department; generate 48h activation token; save; send activation email
    - `updateUser(command)`: update role/department/contact; re-validate Business_Owner uniqueness if role changed
    - `deactivateUser(userId)`: soft-delete; reassign pending expenses to next approver or Business_Owner (Req 2.6); block authentication
    - `listUsers(businessId, pageable)`: paginated, exclude soft-deleted by default
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7_
  - [ ] 15.2 Implement `UserController` at `/api/v1/users`
    - `POST /` → createUser (Admin, Business_Owner); `PUT /{id}` → updateUser; `DELETE /{id}` → deactivateUser; `GET /` → listUsers
    - Apply `@PreAuthorize` with `TenantGuard` to ensure same-business scope
    - _Requirements: 2.1, 17.1_
  - [ ]* 15.3 Write property test for single active Business Owner invariant (Property 4)
    - **Property 4: Single Active Business Owner Invariant**
    - **Validates: Requirements 2.7**
    - Generate sequences of user role assignments; assert count of active BUSINESS_OWNER ≤ 1 at all times
  - [ ]* 15.4 Write property test for user activation token invariant (Property 5)
    - **Property 5: User Activation Token Invariant**
    - **Validates: Requirements 2.2**
    - Generate user creation timestamps; assert activationExpiresAt = createdAt + 48h (±1 min)
  - [ ]* 15.5 Write property test for pending expense reassignment on approver deactivation (Property 6)
    - **Property 6: Pending Expense Reassignment on Approver Deactivation**
    - **Validates: Requirements 2.6**
    - Deactivate approver with N pending expenses; assert all N are reassigned to next approver or Business_Owner

- [ ] 16. Department, Category, and Policy Use Cases and Controllers
  - [ ] 16.1 Implement `DepartmentManagementUseCase` in `usecase`
    - `createDepartment`: unique name per business
    - `updateDepartment`: name and description
    - `deactivateDepartment`: check active-employee flag conflict (Req 3.4); require reassignment if active employees exist; soft-delete
    - `listDepartments`: include active employee count and current-month budget utilisation
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5_
  - [ ] 16.2 Implement `DepartmentController` at `/api/v1/departments`
    - `POST /`, `PUT /{id}`, `DELETE /{id}`, `GET /`; Admin only
    - _Requirements: 3.1, 17.1_
  - [ ] 16.3 Implement `ExpenseCategoryManagementUseCase` in `usecase`
    - `createCategory`, `updateCategory`, `setCategoryLimit`, `removeCategoryLimit`, `deactivateCategory`
    - Both Admin and Business_Owner can perform all actions
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5_
  - [ ] 16.4 Implement `CategoryController` at `/api/v1/categories`
    - `POST /`, `PUT /{id}`, `PATCH /{id}/limit`, `DELETE /{id}/limit`, `DELETE /{id}`
    - _Requirements: 4.1, 4.5, 17.1_
  - [ ] 16.5 Implement `SpendingPolicyManagementUseCase` in `usecase`
    - `setEmployeeMonthlyLimit(employeeId, limit)`: upsert policy for EMPLOYEE_MONTHLY type
    - `setDepartmentMonthlyBudget(departmentId, limit)`: upsert policy for DEPARTMENT_MONTHLY type
    - Both Admin and Business_Owner can perform all actions
    - _Requirements: 5.1, 5.2, 5.6_
  - [ ] 16.6 Implement `PolicyController` at `/api/v1/policies`
    - `PUT /employee/{userId}`, `PUT /department/{deptId}`; Admin and Business_Owner
    - _Requirements: 5.1, 5.2, 17.1_
  - [ ]* 16.7 Write unit tests for department deactivation (employee flag conflict), category limit enforcement, and policy upsert
    - Verify conflict detection raises data integrity alert; verify category limit blocks submission; verify upsert replaces existing policy
    - _Requirements: 3.4, 4.2, 5.1_

- [ ] 17. Expense Submission Use Cases and Controller
  - [ ] 17.1 Implement `ExpenseSubmissionUseCase` in `usecase/expense`
    - `createDraft(command)`: persist `DRAFT` expense; validate amount positive, two decimal places, category active, department active
    - `updateDraft(command)`: allow edit of all fields while status is `DRAFT`
    - `deleteDraft(expenseId, actor)`: soft-delete `DRAFT` expense
    - `submitExpense(expenseId, actor)`: run `ExpenseDateValidator`; run `SpendingPolicyChain`; check attachment requirement (amount > 500 requires ≥ 1 attachment); call `ExpenseStateMachine.transition(DRAFT, SUBMITTED)`; set `submittedAt`; determine `approvalTier` via `ApprovalChainResolver`; set `currentApproverId`; publish `ExpenseSubmittedEvent`
    - `resubmitExpense(expenseId, actor)`: allowed only when status is `REJECTED`; reset approval chain; increment `resubmissionCount`; set `lastResubmittedAt`; transition to `SUBMITTED`; publish event
    - _Requirements: 6.1, 6.2, 6.4, 6.5, 6.7, 6.8, 6.9, 7.1, 7.2, 7.3, 7.4_
  - [ ] 17.2 Implement `AttachmentUploadUseCase` in `usecase/expense`
    - Run `AttachmentValidator.validate()` (magic bytes + size)
    - Call `AttachmentStoragePort.store()`; persist `Attachment` record with `storageKey`
    - _Requirements: 6.2, 6.3_
  - [ ] 17.3 Implement `ExpenseController` at `/api/v1/expenses`
    - `POST /` → createDraft; `PUT /{id}` → updateDraft; `DELETE /{id}` → deleteDraft; `POST /{id}/submit` → submitExpense; `POST /{id}/resubmit` → resubmitExpense
    - `POST /{id}/attachments` (multipart) → AttachmentUploadUseCase
    - Employee role enforced via `@PreAuthorize` and `ExpenseGuard`
    - _Requirements: 6.1, 6.4, 6.8, 6.9, 7.1, 7.2, 17.1_
  - [ ]* 17.4 Write unit tests for `ExpenseSubmissionUseCase`
    - Draft creation, successful submission, submission without required attachment blocked, date policy rejection, spending policy rejection, resubmission increments counter, edit blocked when SUBMITTED
    - _Requirements: 6.1, 6.2, 6.4, 6.7, 7.2, 7.3, 7.4_

- [ ] 18. File Storage Adapters
  - [ ] 18.1 Implement `LocalFileSystemStorageAdapter` in `adapter/storage`
    - Store files under `${bems.storage.local.base-path}/{businessId}/{expenseId}/{uuid}.{ext}`
    - Implement `retrieve` returning `InputStream`; implement `delete`
    - _Requirements: 6.3_
  - [ ] 18.2 Implement `S3StorageAdapter` in `adapter/storage`
    - Use AWS SDK v2 S3 client; key structure `{businessId}/{expenseId}/{uuid}.{ext}`
    - `store`: `PutObjectRequest` with content-type; return storage key
    - `retrieve`: `GetObjectRequest` returning `InputStream`
    - `delete`: `DeleteObjectRequest`
    - Generate pre-signed URLs (1-hour expiry) for client download via `generatePresignedUrl(storageKey)`
    - Select adapter via `@ConditionalOnProperty(name = "bems.storage.mode", havingValue = "s3")`
    - _Requirements: 6.3_
  - [ ]* 18.3 Write unit tests for both storage adapters
    - Mock S3 client; verify key structure; verify pre-signed URL expiry; verify local path structure
    - _Requirements: 6.3_

- [ ] 19. Checkpoint — Core Submission Flow Compiles and Unit Tests Pass
  - Ensure `bems-domain` and `bems-application` compile cleanly; run all unit tests; ask the user if questions arise.

- [ ] 20. Approval Workflow Use Cases and Controller
  - [ ] 20.1 Implement `ApprovalUseCase` in `usecase/expense`
    - `managerApprove(expenseId, comment, actor)`: verify expense is `SUBMITTED` and actor is the department Manager or active Delegate; transition via state machine; apply tier shortcut (< ₹5,000 → `FULLY_APPROVED`); advance `currentApproverId` to next in chain; append `ApprovalStep`; publish event
    - `managerReject(expenseId, comment, actor)`: mandatory comment; transition to `REJECTED`; record rejection reason; publish `ExpenseRejectedEvent`
    - `accountantApprove(expenseId, comment, actor)`: verify `MANAGER_APPROVED` and Accountant role; transition; apply tier shortcut (₹5,000–₹50,000 → `FULLY_APPROVED`); publish event
    - `accountantReject(expenseId, comment, actor)`: mandatory comment; transition to `REJECTED`; publish rejection event
    - `businessOwnerApprove(expenseId, comment, actor)`: verify `ACCOUNTANT_APPROVED` and Business_Owner role; transition to `FULLY_APPROVED`; publish event
    - `businessOwnerReject(expenseId, comment, actor)`: mandatory comment; transition to `REJECTED`
    - `businessOwnerOverride(expenseId, comment, actor)`: allow override from any intermediate status (`SUBMITTED`, `MANAGER_APPROVED`, `ACCOUNTANT_APPROVED`) directly to `FULLY_APPROVED`; mandatory comment; record in audit log with override flag
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 9.1, 9.2, 9.3, 9.4, 10.1, 10.2, 10.3, 10.4, 10.5, 10.6_
  - [ ] 20.2 Implement `DelegateApproverUseCase` in `usecase`
    - `setDelegate(managerId, delegateId, startDate, endDate)`: validate same business, validate `startDate ≤ endDate`, persist
    - `deactivateDelegate(delegationId)`: set `is_active = false`
    - Scheduled job (`@Scheduled(cron = "0 0 0 * * *")`): auto-deactivate all delegations where `end_date < today UTC`
    - _Requirements: 8.5, 8.6_
  - [ ] 20.3 Implement `ApprovalController` at `/api/v1/expenses/{id}/approvals` and `DelegateController` at `/api/v1/delegates`
    - `POST /{id}/approvals/approve`, `POST /{id}/approvals/reject`, `POST /{id}/approvals/override`
    - `POST /delegates`, `DELETE /delegates/{id}`
    - Apply `@PreAuthorize` with role and `ExpenseGuard` checks
    - _Requirements: 8.1, 10.4, 17.1_
  - [ ]* 20.4 Write unit tests for approval workflow
    - Manager approval tier shortcuts, mandatory comment on reject, override records in audit log, delegate active period enforcement (before/after start/end date)
    - _Requirements: 8.2, 8.4, 10.5, 8.6_

- [ ] 21. Escalation Scheduler
  - [ ] 21.1 Implement `EscalationSchedulerService` in `usecase`
    - `@Scheduled(cron = "0 0 * * * *")` (hourly): query all expenses in `SUBMITTED` status where no active delegate exists and `DATEDIFF(now, submitted_at) > escalation_threshold_days` (per `business_configs.escalation_threshold_days`, default 7)
    - Set `is_escalated = true` on matching expenses; do NOT change status
    - Publish `ExpenseEscalatedEvent` for each newly escalated expense (triggers notification to Admin + Business_Owner)
    - _Requirements: 8.1, 8.7_
  - [ ]* 21.2 Write property test for escalation threshold enforcement (Property 14)
    - **Property 14: Escalation Threshold Enforcement**
    - **Validates: Requirements 8.1**
    - Generate arbitrary `(submittedAt, currentTime, thresholdDays)` triples; assert escalation triggered iff elapsed days > threshold

- [ ] 22. Reimbursement Processing Use Cases and Controller
  - [ ] 22.1 Implement `ReimbursementUseCase` in `usecase`
    - `reimburseLine(expenseId, paymentReference, paymentMethod, paymentDate, actor)`: verify status is `FULLY_APPROVED`; transition to `REIMBURSED`; persist payment fields; publish `ExpenseReimbursedEvent`
    - Guard: reject any attempt to set `REIMBURSED` unless current status is `FULLY_APPROVED` (throw `InvalidStateTransitionException`)
    - `listFullyApprovedPendingReimbursement(filters, pageable)`: filter by department/employee/category
    - `listReimbursed(filters, pageable)`: filter by date range/department/employee; show payment reference and date
    - _Requirements: 11.1, 11.2, 11.3, 11.4, 11.5_
  - [ ] 22.2 Implement `ReimbursementController` at `/api/v1/expenses/{id}/reimbursements`
    - `POST /{id}/reimbursements` → reimburse; `GET /reimbursements/pending`; `GET /reimbursements/completed`
    - Accountant role only
    - _Requirements: 11.1, 11.4, 11.5, 17.1_
  - [ ]* 22.3 Write unit tests for `ReimbursementUseCase`
    - Successful reimbursement, guard rejects non-FULLY_APPROVED, payment fields persisted correctly
    - _Requirements: 11.1, 11.3_

- [ ] 23. Notification System
  - [ ] 23.1 Implement domain event classes and `NotificationEventHandler` in `usecase/notification`
    - Create domain event classes: `ExpenseSubmittedEvent`, `ExpenseApprovedEvent`, `ExpenseRejectedEvent`, `ExpenseReimbursedEvent`, `ExpenseEscalatedEvent`, `UserLockedEvent`, `UserActivationRequestedEvent`
    - `NotificationEventHandler`: annotated `@TransactionalEventListener(phase = AFTER_COMMIT)` on each event handler method; run on `@Async` `notification-pool` thread pool (5–20 threads)
    - For each event, dispatch both in-app (INSERT into `notifications`) and email (`JavaMailSender`)
    - _Requirements: 6.6, 8.2, 8.3, 13.1, 13.2, 13.3, 13.4_
  - [ ] 23.2 Implement notification failure handling and retry scheduler
    - Wrap email sending in try/catch; on failure insert into `notification_failures` with `retry_count=0`, `next_retry_at = now + 1min`
    - `@Scheduled(fixedDelay = 60000)` retry job: fetch failures with `retry_count < 3` and `next_retry_at <= now`; attempt resend; on success mark resolved; on failure increment count with exponential back-off (1min, 5min, 25min)
    - Reimbursement: notification failure must NOT roll back primary transaction (Req 11.2)
    - _Requirements: 11.2, 13.1_
  - [ ] 23.3 Implement `EmailAdapter` in `adapter/mail`
    - Use `JavaMailSender` with Thymeleaf templates for each notification type
    - _Requirements: 13.1_
  - [ ] 23.4 Implement `NotificationController` at `/api/v1/notifications`
    - `GET /` → paginated list of in-app notifications for authenticated user (unread distinguished)
    - `PATCH /{id}/read` → mark as read, record `read_at`
    - _Requirements: 13.6, 13.7_
  - [ ]* 23.5 Write unit tests for notification dispatch and retry
    - Verify AFTER_COMMIT fires only after commit; verify email failure does not roll back business transaction; verify retry logic applies exponential back-off; verify 3-attempt max
    - _Requirements: 11.2, 13.1_

- [ ] 24. Audit Log (AOP Aspect)
  - [ ] 24.1 Implement `@AuditableAction` annotation and `AuditLogAspect` in `shared/audit`
    - `@AuditableAction(entityType, actionType)` annotation for service methods
    - `AuditLogAspect`: `@AfterReturning` around all `@AuditableAction` methods
    - Before invocation: capture entity's previous state via serialisation to JSON string
    - After invocation: capture new state; build `AuditLog` record with entityType, entityId, actionType, actorId, actorRole, previousState, newState, UTC timestamp
    - Insert via `JdbcTemplate` native INSERT (bypasses JPA to guarantee no UPDATE path)
    - Apply `@AuditableAction` to all create, update, status-change, login, logout, soft-delete service methods
    - _Requirements: 18.1, 18.2, 18.3_
  - [ ] 24.2 Implement `AuditController` at `/api/v1/audit`
    - `GET /` → paginated, filterable by dateRange, actorUser, entityType, actionType (Business_Owner and Accountant scoped)
    - Business_Owner sees all entries; Accountant sees only `EXPENSE` and `REIMBURSEMENT` entity types
    - _Requirements: 18.4, 18.5, 17.1_
  - [ ]* 24.3 Write property test for audit log completeness (Property 17)
    - **Property 17: Audit Log Completeness**
    - **Validates: Requirements 18.1, 18.2**
    - Perform N auditable operations; assert exactly N new audit log entries exist with correct field values after each operation
  - [ ]* 24.4 Write property test for audit log immutability (Property 18)
    - **Property 18: Audit Log Immutability**
    - **Validates: Requirements 18.3**
    - Attempt modifications/deletions on audit log via all application code paths; assert total count is monotonically non-decreasing

- [ ] 25. Checkpoint — Approval, Reimbursement, Notification, and Audit Integrated
  - Ensure all tests pass for tasks 20–24; verify audit log entries appear in integration scenarios; ask the user if questions arise.

- [ ] 26. Reporting and Export
  - [ ] 26.1 Implement `ReportQueryService` in `usecase/report`
    - `getEmployeeExpenseReport(filters, pageable)`: expenses for authenticated employee; filterable by dateRange, category, status
    - `getDepartmentExpenseReport(filters, pageable)`: all expenses in Manager's department; filterable by dateRange, category, employee, status
    - `getBusinessFinancialReport(filters, pageable)`: all expenses across all departments (Accountant); filterable by dateRange, department, category, status
    - `getBusinessLevelReport(filters, pageable)`: all expenses + department budgets + category utilisation (Admin); filterable by dateRange, department
    - `getOwnerReport(filters, pageable)`: superset of all above (Business_Owner)
    - All queries read from current DB state — no caching of stale data
    - Use streaming queries / cursor-based pagination for large result sets
    - _Requirements: 14.1, 14.2, 14.3, 14.4, 14.5, 14.6_
  - [ ] 26.2 Implement `PdfReportAdapter` in `adapter/export`
    - Use OpenPDF `PdfWriter` with streaming; write table rows incrementally to `HttpServletResponse` `OutputStream`
    - Content-Disposition: `attachment; filename="report-{timestamp}.pdf"`
    - _Requirements: 14.7, 14.8_
  - [ ] 26.3 Implement `XlsxReportAdapter` in `adapter/export`
    - Use Apache POI `SXSSFWorkbook` (streaming); flush every 100 rows; write to response `OutputStream`
    - Content-Disposition: `attachment; filename="report-{timestamp}.xlsx"`
    - _Requirements: 14.7, 14.8_
  - [ ] 26.4 Implement `ReportController` at `/api/v1/reports`
    - `GET /expenses` → role-scoped report query; supports `page`, `size`, `sortBy` (default size 20, max 100)
    - `GET /expenses/export/pdf` → `PdfReportAdapter`
    - `GET /expenses/export/xlsx` → `XlsxReportAdapter`
    - Role-based access via `@PreAuthorize`
    - _Requirements: 14.1, 14.7, 17.1, 20.5_
  - [ ]* 26.5 Write unit tests for report adapters
    - Verify PDF content-disposition header; verify XLSX flushes at row 100; verify report filtered correctly per role
    - _Requirements: 14.7, 14.8_

- [ ] 27. Dashboard Endpoints
  - [ ] 27.1 Implement `DashboardQueryService` in `usecase`
    - Employee dashboard: total expenses this month, pending, approved, reimbursed, remaining monthly budget
    - Manager dashboard: department expenses this month, expenses pending their approval, department budget utilisation %
    - Accountant dashboard: total awaiting verification, total reimbursements this month, total reimbursement value
    - Admin dashboard: total active users, total departments, expenses submitted this month, policy limit breaches this month
    - Business_Owner dashboard: superset of Manager + Accountant + Admin metrics + overall business spend vs. total budget
    - _Requirements: 15.1, 15.2, 15.3, 15.4, 15.5_
  - [ ] 27.2 Implement `DashboardController` at `/api/v1/dashboard`
    - Single `GET /` endpoint; returns role-specific payload based on authenticated user's role
    - _Requirements: 15.1, 17.1_
  - [ ]* 27.3 Write unit tests for `DashboardQueryService`
    - Each role returns the correct metrics; Business_Owner includes superset; remaining budget calculation correct
    - _Requirements: 15.1, 15.2, 15.3, 15.4, 15.5_

- [ ] 28. Multi-Tenant Data Isolation — Soft Delete and Query Exclusion
  - [ ] 28.1 Verify and harden tenant isolation and soft-delete filter across all repositories
    - Confirm `tenantFilter` is enabled on every business-scoped entity query
    - Confirm all standard query results exclude `deleted_at IS NOT NULL` records
    - Add `@PreAuthorize` + `TenantGuard` to every controller method that accepts a resource ID to prevent cross-tenant access
    - Log unauthorised access attempts in audit log via `GlobalExceptionHandler` on `AccessDeniedException`
    - _Requirements: 1.5, 17.2, 19.1, 19.2_
  - [ ]* 28.2 Write property test for multi-tenant data isolation (Property 2)
    - **Property 2: Multi-Tenant Data Isolation**
    - **Validates: Requirements 1.5**
    - Create records for B1 and B2; for any query executed in B2 context, assert B1 records never appear
  - [ ]* 28.3 Write property test for soft delete record state (Property 19)
    - **Property 19: Soft Delete Record State**
    - **Validates: Requirements 19.1**
    - Soft-delete entities; assert `deleted_at` non-null, `deleted_by` = actor ID, no other fields changed
  - [ ]* 28.4 Write property test for soft-deleted records excluded from standard queries (Property 20)
    - **Property 20: Soft-Deleted Records Excluded from Standard Queries**
    - **Validates: Requirements 19.2**
    - Soft-delete a subset; run standard queries; assert only `deleted_at IS NULL` records returned

- [ ] 29. Pagination Standards
  - [ ] 29.1 Implement `PaginationUtils` and apply to all list endpoints
    - Default page size = 20, maximum page size = 100; enforce maximum in each controller
    - Wrap all paginated responses in `ApiResponse` with `page`, `size`, `totalElements`, `totalPages`
    - Apply `page`, `size`, `sortBy` query parameters to all list endpoints: businesses, users, departments, categories, expenses, notifications, audit log, reports
    - _Requirements: 20.5_
  - [ ]* 29.2 Write property test for pagination result size bound (Property 21)
    - **Property 21: Pagination Result Size Bound**
    - **Validates: Requirements 20.5**
    - Generate arbitrary valid `(page, size)` where 1 ≤ size ≤ 100; assert response contains at most `size` records; assert total across all pages equals total matching count

- [ ] 30. OpenAPI Documentation
  - [ ] 30.1 Add springdoc-openapi 2 annotations to all controllers
    - Annotate each endpoint with `@Operation`, `@ApiResponse`, `@Parameter`
    - Document all request/response DTOs with `@Schema`
    - Add security scheme definition for Bearer JWT
    - Configure springdoc to serve at `/api/v1/docs`
    - _Requirements: 20.6_
  - [ ]* 30.2 Write a smoke test asserting `/api/v1/docs` returns HTTP 200 with valid OpenAPI JSON
    - _Requirements: 20.6_

- [ ] 31. Performance and Security Hardening
  - [ ] 31.1 Implement input sanitisation and CORS configuration
    - Apply OWASP Java Encoder to `title`, `description`, and comment fields before persistence
    - Validate all inputs with Bean Validation (`@NotBlank`, `@Size`, `@Pattern`) on all request DTOs
    - Configure `CorsFilter` with allowed origins from `application.yml`; restrict to approved client origins
    - _Requirements: 22.2, 22.3_
  - [ ] 31.2 Implement HTTPS enforcement and sensitive-field protection
    - Add `HttpToHttpsRedirectFilter` (or configure Tomcat connector redirect for HTTP→HTTPS)
    - Ensure `password_hash`, JWT secrets, and DB credentials never appear in API responses or logs
    - Add log masking for PII fields (full name, email) and sensitive values
    - _Requirements: 22.1, 22.6_
  - [ ] 31.3 Implement GDPR compatibility fields
    - Confirm `data_subject_id` on `User` entity; confirm PII columns (`full_name`, `email`) are in dedicated columns
    - Log all PII field access in `audit_log` with `pii_accessed = true`
    - _Requirements: 24.1, 24.2, 24.3_
  - [ ] 31.4 Add database performance indexes verification
    - Confirm all indexes defined in migrations are present: `(business_id, status)`, `(business_id, submitter_id)`, `(business_id, department_id, status)`, `(current_approver_id)` partial, `(recipient_id, is_read)` partial
    - Confirm HikariCP config: `minimumIdle=5`, `maximumPoolSize=50` in `application.yml`
    - _Requirements: 21.1, 21.4_
  - [ ]* 31.5 Write security integration tests
    - CORS rejects disallowed origins; HTTPS redirect fires on HTTP request; PII fields absent from API responses; sensitive fields absent from logs
    - _Requirements: 22.1, 22.3, 22.6_

- [ ] 32. Property-Based Tests — Remaining Properties
  - [ ] 32.1 Wire all 23 property tests in `bems-domain/src/test/java/.../property/` and confirm `@Property(tries = 200)` minimum and `@Tag("property-based")` on all
    - Confirm Properties 1–23 each have a dedicated test class in the property package
    - Confirm each test references the correct requirements clause in its Javadoc
    - Run `mvn test -pl bems-domain -Dgroups="property-based"` and verify all 23 properties pass
    - _Requirements: All (see individual property tasks above)_

- [ ] 33. Integration Tests (Testcontainers)
  - [ ] 33.1 Set up Testcontainers base test class in `bems-integration-test`
    - Start PostgreSQL 16 container; run Flyway migrations on test DB; configure `application-test.yml` with container JDBC URL
    - Create `IntegrationTestBase` with `@SpringBootTest(webEnvironment = RANDOM_PORT)` + REST Assured setup
    - _Requirements: All_
  - [ ] 33.2 Implement full expense lifecycle integration test
    - Scenario: Register user → Submit expense (all three tiers separately) → Approve through full chain → Reimburse
    - Assert status transitions at each step; assert `approval_steps` rows appended; assert audit log entry at each step
    - _Requirements: 6.4, 8.2, 9.2, 10.2, 11.1, 12.1, 18.1_
  - [ ] 33.3 Implement cross-tenant isolation integration test
    - Create two businesses with users and expenses; authenticate as Business 2; assert GET requests for Business 1 data return 404 or empty
    - _Requirements: 1.5_
  - [ ] 33.4 Implement soft-delete exclusion integration test
    - Create entities; soft-delete a subset; assert standard queries exclude soft-deleted; assert Business_Owner can see deleted with explicit filter
    - _Requirements: 19.1, 19.2_
  - [ ] 33.5 Implement notification async dispatch integration test
    - Submit expense; assert `notifications` table row created for first approver after transaction commits; does not test SMTP delivery
    - _Requirements: 6.6, 13.1_
  - [ ] 33.6 Implement XLSX export performance integration test
    - Generate 10,000 synthetic expense records; request XLSX export; assert response received within 10 seconds
    - _Requirements: 14.8, 21.2_
  - [ ] 33.7 Implement spending policy enforcement integration test
    - Set employee monthly limit; submit expenses that approach then exceed limit; assert last submission rejected with correct error detail
    - _Requirements: 5.3, 5.5_
  - [ ] 33.8 Implement approval escalation integration test
    - Submit expense; advance system clock by threshold+1 days; run escalation scheduler; assert `is_escalated = true` and notification dispatched
    - _Requirements: 8.1, 8.7_
  - [ ]* 33.9 Write additional integration tests for RBAC enforcement
    - Attempt each role-restricted action with an unauthorised role; assert HTTP 403 and audit log entry
    - _Requirements: 17.2_

- [ ] 34. Final Checkpoint — All Tests Pass
  - Run `mvn verify` across all modules; ensure zero test failures; ensure all 23 property tests pass; ensure all integration tests pass; ask the user if questions arise.

---

## Notes

- Tasks marked with `*` are optional test sub-tasks and can be skipped for a faster MVP build; core implementation tasks are never optional.
- Every task references specific acceptance criteria for full traceability from code to requirements.
- The domain layer (`bems-domain`) has zero Spring/JPA dependencies; all 23 property tests run without an application context.
- Checkpoints (tasks 8, 19, 25, 34) ensure incremental validation at logical boundaries.
- The dependency graph below sequences tasks to maximise parallel execution while respecting file and module boundaries.
- Properties 2, 3, 6, 17, 18, 19, 20 require a running database and are placed in later waves after persistence adapters are complete.

## Task Dependency Graph

```json
{
  "waves": [
    {
      "id": 0,
      "tasks": ["1.1"]
    },
    {
      "id": 1,
      "tasks": ["2.1", "2.2"]
    },
    {
      "id": 2,
      "tasks": ["3.1", "4.1", "5.1", "6.1"]
    },
    {
      "id": 3,
      "tasks": ["3.2", "3.3", "4.2", "4.3", "5.2", "5.3", "5.4", "5.5", "7.1", "7.3", "7.5", "7.7"]
    },
    {
      "id": 4,
      "tasks": ["7.2", "7.4", "7.6", "7.8", "9.1", "9.2", "9.3", "9.4"]
    },
    {
      "id": 5,
      "tasks": ["9.5", "9.6", "9.7", "9.8"]
    },
    {
      "id": 6,
      "tasks": ["10.1", "10.2", "10.3"]
    },
    {
      "id": 7,
      "tasks": ["10.4", "10.5", "11.1", "11.3", "11.4", "12.1", "12.2"]
    },
    {
      "id": 8,
      "tasks": ["11.2", "11.5", "11.6", "12.3", "13.1", "14.1"]
    },
    {
      "id": 9,
      "tasks": ["13.2", "13.3", "14.2", "14.3", "14.4", "15.1", "16.1"]
    },
    {
      "id": 10,
      "tasks": ["14.4", "15.2", "15.3", "15.4", "15.5", "16.2", "16.3", "16.5"]
    },
    {
      "id": 11,
      "tasks": ["16.4", "16.6", "16.7", "17.1", "17.2", "18.1", "18.2"]
    },
    {
      "id": 12,
      "tasks": ["17.3", "17.4", "18.3", "20.1", "20.2", "21.1", "22.1"]
    },
    {
      "id": 13,
      "tasks": ["20.3", "20.4", "21.2", "22.2", "22.3", "23.1", "24.1"]
    },
    {
      "id": 14,
      "tasks": ["23.2", "23.3", "23.4", "23.5", "24.2", "26.1", "27.1"]
    },
    {
      "id": 15,
      "tasks": ["24.3", "24.4", "26.2", "26.3", "27.2", "28.1"]
    },
    {
      "id": 16,
      "tasks": ["26.4", "26.5", "27.3", "28.2", "28.3", "28.4", "29.1"]
    },
    {
      "id": 17,
      "tasks": ["29.2", "30.1", "31.1", "31.2", "31.3", "31.4"]
    },
    {
      "id": 18,
      "tasks": ["30.2", "31.5", "32.1"]
    },
    {
      "id": 19,
      "tasks": ["33.1"]
    },
    {
      "id": 20,
      "tasks": ["33.2", "33.3", "33.4", "33.5", "33.6", "33.7", "33.8"]
    },
    {
      "id": 21,
      "tasks": ["33.9"]
    }
  ]
}
```
