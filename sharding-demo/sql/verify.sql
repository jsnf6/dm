-- verify created sharding layout
SELECT schema_name AS db_name
FROM information_schema.schemata
WHERE schema_name LIKE 'shard_order\_%'
ORDER BY schema_name;

SELECT table_schema AS db_name, table_name
FROM information_schema.tables
WHERE table_schema LIKE 'shard_order\_%'
ORDER BY table_schema, table_name;
