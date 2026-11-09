# Room schema evidence

- `dev.veyra.data.room.VeyraDatabase/2.json` is exported by Room 2.8.5 KSP.
- `1.json` is an **authored, never-shipped relational baseline fixture**, derived from the initial
  schema-2 export by removing the crossfade preferences and download cleanup-intent columns.
  Its fixture identity is deterministic, not claimed as a historical KSP/release export.
- `RoomMigrationTest` creates that baseline with Room's real `MigrationTestHelper`, copies authored
  rows for every normalized table, executes `MIGRATION_1_2`, validates against the actual schema-2
  export, and opens the result through the production store. A missing migration must fail.
- There is no destructive fallback and no JSON database. These JSON files are build/test schema
  descriptions only. Actual application data lives in SQLite tables.
