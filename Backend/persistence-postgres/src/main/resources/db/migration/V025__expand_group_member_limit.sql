ALTER TABLE group_admission_policy
    DROP CONSTRAINT group_admission_policy_member_limit;

ALTER TABLE group_admission_policy
    ADD CONSTRAINT group_admission_policy_member_limit
    CHECK (max_members BETWEEN 1 AND 1000000);
