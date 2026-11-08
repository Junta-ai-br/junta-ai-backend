-- Additive metadata only. Do not classify or modify any existing category.
ALTER TABLE categories ADD COLUMN standard_key VARCHAR(32);
ALTER TABLE categories ADD CONSTRAINT ck_category_standard_key
    CHECK (standard_key IS NULL OR standard_key IN ('SALARY', 'REFUND', 'EXTRA_INCOME', 'BONUSES', 'GIFTS_RECEIVED', 'FOOD', 'HOUSING', 'TRANSPORT', 'HEALTH', 'EDUCATION', 'LEISURE', 'STREAMING', 'GAMBLING', 'OTHER'));
CREATE UNIQUE INDEX uq_category_user_standard_key
    ON categories (user_id, standard_key) WHERE standard_key IS NOT NULL;

-- Explicit, separately approved adoption may assign NULL -> key. Once assigned,
-- even direct SQL cannot replace/clear the identity. Names/types remain editable.
CREATE FUNCTION guard_category_standard_key() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.standard_key IS NOT NULL AND NEW.standard_key IS DISTINCT FROM OLD.standard_key THEN
        RAISE EXCEPTION 'category_standard_key_immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER category_standard_key_immutable
    BEFORE UPDATE OF standard_key ON categories
    FOR EACH ROW EXECUTE FUNCTION guard_category_standard_key();
