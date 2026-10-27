-- Preserve the existing API contract: a text-only review has rating zero.
ALTER TABLE order_review_items DROP CONSTRAINT chk_order_review_items_rating;
ALTER TABLE order_review_items ADD CONSTRAINT chk_order_review_items_rating CHECK (rating >= 0 AND rating <= 5);
