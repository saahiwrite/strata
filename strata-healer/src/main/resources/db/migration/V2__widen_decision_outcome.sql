-- AWAITING_APPROVAL is 17 characters but the outcome column only held 16
ALTER TABLE decisions ALTER COLUMN outcome SET DATA TYPE VARCHAR(32);
