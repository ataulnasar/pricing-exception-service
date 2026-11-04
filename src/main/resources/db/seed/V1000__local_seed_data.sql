-- Local demo data. Loaded only in the default (H2) profile, see application.properties.
-- Users and applications from docs/design.md.

INSERT INTO app_user (id, name, role, approval_limit_bps) VALUES
    ('rm-anna',         'Anna Lind',        'RM',               NULL),
    ('rm-erik',         'Erik Berg',        'RM',               NULL),
    ('rev-sara',        'Sara Holm',        'REVIEWER',         50),
    ('rev-johan',       'Johan Ek',         'REVIEWER',         150),
    ('mortgage-system', 'Mortgage process', 'MORTGAGE_PROCESS', NULL);

INSERT INTO mortgage_application (id, customer_ref, standard_rate_bps, status, owner_rm_id) VALUES
    ('MA-1001', 'CUST-0001', 425, 'OPEN',   'rm-anna'),
    ('MA-1002', 'CUST-0002', 410, 'OPEN',   'rm-anna'),
    ('MA-1003', 'CUST-0003', 450, 'OPEN',   'rm-erik'),
    ('MA-1004', 'CUST-0004', 395, 'CLOSED', 'rm-erik');

-- One APPROVED (MA-1001), one PENDING (MA-1002) and one DECLINED (MA-1003) request, with history.
INSERT INTO pricing_exception_request
    (id, application_id, requested_by, discount_bps, reason, status, supersedes_id,
     decided_by, decided_at, decision_comment, approved_discount_bps, created_at, version, active_application_id)
VALUES
    ('00000000-0000-0000-0000-000000000001', 'MA-1001', 'rm-anna', 30,
     'Long-standing customer with three other products', 'APPROVED', NULL,
     'rev-sara', TIMESTAMP WITH TIME ZONE '2026-01-10 09:30:00+00', 'Within limit, approved',
     30, TIMESTAMP WITH TIME ZONE '2026-01-09 14:00:00+00', 1, 'MA-1001'),
    ('00000000-0000-0000-0000-000000000002', 'MA-1002', 'rm-anna', 80,
     'Competing offer from another bank', 'PENDING', NULL,
     NULL, NULL, NULL,
     NULL, TIMESTAMP WITH TIME ZONE '2026-01-12 10:15:00+00', 0, 'MA-1002'),
    ('00000000-0000-0000-0000-000000000003', 'MA-1003', 'rm-erik', 60,
     'Customer threatened to leave', 'DECLINED', NULL,
     'rev-johan', TIMESTAMP WITH TIME ZONE '2026-01-13 16:45:00+00', 'Margin too low on this product',
     NULL, TIMESTAMP WITH TIME ZONE '2026-01-13 11:20:00+00', 1, NULL);

INSERT INTO request_event (request_id, type, actor_id, occurred_at, from_status, to_status, comment) VALUES
    ('00000000-0000-0000-0000-000000000001', 'SUBMITTED', 'rm-anna',  TIMESTAMP WITH TIME ZONE '2026-01-09 14:00:00+00', NULL,      'PENDING',  NULL),
    ('00000000-0000-0000-0000-000000000001', 'APPROVED',  'rev-sara', TIMESTAMP WITH TIME ZONE '2026-01-10 09:30:00+00', 'PENDING', 'APPROVED', 'Within limit, approved'),
    ('00000000-0000-0000-0000-000000000002', 'SUBMITTED', 'rm-anna',  TIMESTAMP WITH TIME ZONE '2026-01-12 10:15:00+00', NULL,      'PENDING',  NULL),
    ('00000000-0000-0000-0000-000000000003', 'SUBMITTED', 'rm-erik',  TIMESTAMP WITH TIME ZONE '2026-01-13 11:20:00+00', NULL,      'PENDING',  NULL),
    ('00000000-0000-0000-0000-000000000003', 'DECLINED',  'rev-johan', TIMESTAMP WITH TIME ZONE '2026-01-13 16:45:00+00', 'PENDING', 'DECLINED', 'Margin too low on this product');
