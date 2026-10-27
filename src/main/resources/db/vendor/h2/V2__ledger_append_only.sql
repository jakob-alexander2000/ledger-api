CREATE TRIGGER trg_ledger_no_update
BEFORE UPDATE ON ledger_entries
FOR EACH ROW
CALL "com.ledger.custody.persistence.RejectLedgerMutation";

CREATE TRIGGER trg_ledger_no_delete
BEFORE DELETE ON ledger_entries
FOR EACH ROW
CALL "com.ledger.custody.persistence.RejectLedgerMutation";
