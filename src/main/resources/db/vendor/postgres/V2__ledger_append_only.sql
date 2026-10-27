CREATE OR REPLACE FUNCTION reject_ledger_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'ledger_entries is append-only';
END;
$$;

CREATE TRIGGER trg_ledger_no_update
BEFORE UPDATE ON ledger_entries
FOR EACH ROW
EXECUTE FUNCTION reject_ledger_mutation();

CREATE TRIGGER trg_ledger_no_delete
BEFORE DELETE ON ledger_entries
FOR EACH ROW
EXECUTE FUNCTION reject_ledger_mutation();
