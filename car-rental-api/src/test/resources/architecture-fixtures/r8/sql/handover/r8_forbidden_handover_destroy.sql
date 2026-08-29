-- Keywords in comments are documentation, not executable SQL.
-- DELETE FROM handover.handover_evidence;

/*
   DROP TABLE handover.handover_evidence;
   /* TRUNCATE TABLE handover.handover_evidence; */
*/

SELECT 'DELETE FROM handover.handover_evidence' AS documentation_only;
SELECT $$TRUNCATE TABLE handover.handover_evidence$$ AS another_example;
SELECT "DELETE" FROM "handover"."handover_evidence";

MERGE INTO "handover"."handover_evidence" AS target
USING (VALUES (:evidence_id)) AS source(id)
ON target.id = source.id
WHEN MATCHED THEN DELETE;

TRUNCATE TABLE "handover"."handover_import_staging";

ALTER TABLE "handover"."handover_evidence"
ADD CONSTRAINT "fk_handover_booking"
FOREIGN KEY ("booking_id")
REFERENCES "booking"."booking" ("id")
ON DELETE RESTRICT;
