ALTER TABLE reservations
  ADD COLUMN active_slot_key VARCHAR(60)
    GENERATED ALWAYS AS (
      CASE WHEN status = 'CONFIRMED'
           THEN CONCAT(table_id, '_', target_date_time)
           ELSE NULL END
    ) STORED,
  ADD UNIQUE KEY uq_reservations_active_slot (active_slot_key);
