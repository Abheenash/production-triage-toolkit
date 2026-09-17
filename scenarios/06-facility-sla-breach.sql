-- Scenario 06 -> must be caught by OPS003 (facility SLA breach), and by nothing else.
--
-- Story: the facilities queue was not worked over a long weekend. One ticket per priority
-- is backdated past its SLA, so the finding exercises all three SLA bands at once:
--   p1 breaches after 4h  -> backdated 9h
--   p2 breaches after 24h -> backdated 40h
--   p3 breaches after 72h -> backdated 100h
BEGIN;

UPDATE facility_requests
   SET opened_at = now() - interval '9 hours',
       priority  = 'p1',
       status    = 'open',
       acknowledged_at = NULL,
       resolved_at = NULL
 WHERE request_id = (SELECT min(request_id) FROM facility_requests);

UPDATE facility_requests
   SET opened_at = now() - interval '40 hours',
       priority  = 'p2',
       status    = 'in_progress',
       acknowledged_at = now() - interval '39 hours',
       resolved_at = NULL
 WHERE request_id = (SELECT min(request_id) + 1 FROM facility_requests);

UPDATE facility_requests
   SET opened_at = now() - interval '100 hours',
       priority  = 'p3',
       status    = 'open',
       acknowledged_at = NULL,
       resolved_at = NULL
 WHERE request_id = (SELECT min(request_id) + 2 FROM facility_requests);

COMMIT;
