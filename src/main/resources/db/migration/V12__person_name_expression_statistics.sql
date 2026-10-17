-- Statistics for the expression the index of V11 holds, without which the
-- planner may keep reading every person. In its own migration, so no migration
-- runs long enough for the platform to end the transaction Flyway holds open.
ANALYZE person;
