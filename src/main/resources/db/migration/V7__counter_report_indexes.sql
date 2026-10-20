-- Reports filter one counter by each lifecycle timestamp independently.
-- Composite indexes keep each bounded event-range branch selective as order history grows.
CREATE INDEX ix_meal_order_counter_placed_report ON meal_order (counter_id, placed_at);
CREATE INDEX ix_meal_order_counter_accepted_report ON meal_order (counter_id, accepted_at);
CREATE INDEX ix_meal_order_counter_collected_report ON meal_order (counter_id, collected_at);
CREATE INDEX ix_meal_order_counter_expired_report ON meal_order (counter_id, expired_at);
