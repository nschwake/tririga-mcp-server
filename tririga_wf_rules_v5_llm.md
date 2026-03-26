# TRIRIGA WORKFLOW CREATION RULES v5
# For LLM Agents Using SQL-Only MCP Tools
# Format: LLM-optimized. Dense. Authoritative. SQL-only execution model.

---

## SECTION 0 — PLATFORM DETECTION (ALWAYS RUN FIRST)

Run before any other action. Store result. Use platform-appropriate syntax throughout.

| Query | Platform |
|---|---|
| SELECT @@version | SQL Server |
| SELECT version(); | PostgreSQL/MySQL/MariaDB |
| SELECT * FROM v$version; | Oracle |
| SELECT sqlite_version(); | SQLite |
| SELECT * FROM SYSIBMADM.ENV_INST_INFO; | DB2 |

RULE 0.1: Never assume SQL Server. Tririga commonly runs on DB2 or Oracle.

All sequence syntax examples below use DB2. Adapt per detected platform:
- DB2: `SET SESSION.VAR = NEXTVAL FOR SEQ_NAME`
- Oracle: `SELECT SEQ_NAME.NEXTVAL INTO :var FROM DUAL` (inside PL/SQL block)
- PostgreSQL: `SELECT nextval('seq_name')` — store result in agent memory before use
- SQL Server: `SET @var = NEXT VALUE FOR SEQ_NAME`

RULE 0.2: All sequence calls are issued as standalone SQL statements via the MCP SQL tool. Results are stored by the agent and substituted as literal values into subsequent SQL statements. Never use `?` placeholder syntax or `:param` notation in generated SQL — all values must be fully resolved literals at the time of execution.

---

## SECTION 1 — SCHEMA REFERENCE

### 1.1 Core Tables

**WF_TEMPLATE** — one row per (WF_TEMPLATE_ID, WF_TEMPLATE_VERSION)
- Columns: WF_TEMPLATE_ID, WF_TEMPLATE_VERSION, BO_CLASS_TYPE_ID, BO_TYPE_NAME, BO_TYPE_ID, BO_EVENT_NAME, WF_NAME, DESCRIPTION, STATUS_ID, UPDATED_DATE, UPDATED_BY, CREATED_DATE, CREATED_BY, WF_TYPE, SOURCE_WF_TEMPLATE_ID, SOURCE_WF_TEMPLATE_VERSION, TEMPLATE_FLAG, BO_ID, START_DATE, END_DATE, SECONDARY_BO_CLASS_TYPE_ID, SECONDARY_BO_TYPE_ID, ASSOCIATION_NAME, IGNORE_MODULE_WF_FLAG, LOCK_RECORD_FLAG, PROJECT_ID, PROJECT_NAME, INSTANCE_DATA_FLAG, WF_MODIFIER, OBJECT_LABEL_ID
- CRITICAL: Contains BLOB column WF_TEMPLATE_BINARY. NEVER use SELECT * — always name columns explicitly.

**WF_TEMPLATE_STEP** — one row per (WF_TEMPLATE_ID, WF_TEMPLATE_VERSION, STEP_ID) normally; End Switch steps have MULTIPLE rows per STEP_ID (one per converging branch, different PARENT_STEP_ID each)
- Columns: WF_TEMPLATE_ID, WF_TEMPLATE_VERSION, STEP_ID, STEP_TYPE, PARENT_STEP_ID, CREATED_DATE, CREATED_BY

**TASK** — one row per (WF_TEMPLATE_ID, VERSION, TASK_ID)
- Columns: TASK_ID, WF_TEMPLATE_ID, TASK_LABEL, VERSION, CLASSIFICATION_TYPE_ID, OBJECT_TYPE_ID, EVENT_ACTION, DESCRIPTION, ASSIGN_TO_USER, EST_END_IN_DAYS, EST_END_IN_MINS, EST_END_IN_HRS, RECURRENCE_ID, MAP_ID, SOURCE_TASK_ID, ASSIGN_TO_FLAG, TARGET_TASK_ID, SERVICE_CONTEXT, SERVICE_ASSOC_STRING, FILTER_CLASS, FILTER_OBJECT, FILTER_SECTION, FILTER_FIELD, FILTER_OPERATOR, FILTER_VALUE, SUM_SECTION, SUM_FIELD, ASSIGNEE_TASK_ID, TARGET_ASSOC_STRING, TASK_TYPE, LOCK_USER, ASSOC_CLASS_TYPE_ID, ASSOC_OBJECT_TYPE_ID, DELETE_SECTION, USE_MAP, SORT_COUNT, DATE_CONTEXT, TRANSACTION_TYPE, STATUS, PARAMS_MAP_ID, FORMULA_RECALC
- FILTER_* columns are NOT used for workflow filters (exception: FILTER_OBJECT — see Rules 1.10–1.14)
- PARAMS_MAP_ID is non-nullable; set to -1 unless evidence requires otherwise
- MAP_ID = -1 is the null sentinel (not SQL NULL)
- FILTER_OBJECT = -1 is the null sentinel (not SQL NULL)
- FILTER_CLASS = -1 is the null sentinel (not SQL NULL)
- FILTER_OPERATOR = 10 is the observed default

**OBJECT_TYPE_MAP** — one row per (MAP_ID, WF_TEMPLATE_ID, WF_TEMPLATE_VERSION, SRC_MEMBER_ID, TARGET_MEMBER_ID)
- Columns: MAP_ID, SRC_OBJECT_TYPE_ID, SRC_TAB_ID, SRC_MEMBER_ID, TARGET_OBJECT_TYPE_ID, TARGET_TAB_ID, TARGET_MEMBER_ID, MAP_TYPE, CREATED_DATE, CREATED_BY, FIELD_VALUE, WF_TEMPLATE_ID, WF_TEMPLATE_VERSION, SRC_FIELD_VALUE

**EXPRESSION** — one row per EXPRESSION-bearing TASK.MAP_ID
- Columns: ID (=TASK.MAP_ID), FORMULA (VARCHAR 16000)
- Blank formula stored as single space ' ' (never empty string)

**EXPRESSION_PARAM** — zero or more rows per EXPRESSION
- Columns: FORMULA_ID (=TASK.MAP_ID), PARAM_ID (zero-indexed integer), PARAM_STR (VARCHAR 8000, NOT NULL)

**TASK_FILTER** — zero or more rows per task
- Columns: TASK_ID, WF_TEMPLATE_ID, WF_TEMPLATE_VERSION, SEQUENCE_ID, LEFT_SECTION_NAME, LEFT_FIELD_NAME, LEFT_DATA_TYPE, LEFT_LIST_FLAG, OPERATOR, RIGHT_LIST_TYPE, RIGHT_SECTION_NAME, RIGHT_FIELD_NAME, RIGHT_DATA_TYPE, CONSTANT_FLAG, RIGHT_VALUE, RIGHT_LIST_FLAG, ASSOCIATED_TO_TASK, ASSOCIATED_TO_CHILDREN, ASSOCIATED_TO_NAME
- SEQUENCE_ID starts at 1, increments by 1. Manual — NOT sequence-driven.

**WF_STEP_REF** — zero or more rows per step
- Columns: WF_TEMPLATE_ID, WF_TEMPLATE_VERSION, STEP_ID, USAGE_TYPE, REF_TYPE, TASK_REF_TYPE, CONTEXT_TYPE, RECORD_ID, REF_TASK_ID, MODULE_ID, OBJECT_TYPE_ID, SECTION, FIELD, ASSOC
- USAGE_TYPE 1 = primary source; USAGE_TYPE 2 = secondary reference

**SOBJTYPE_FORMULA_HDR** — one row per MAP_TYPE 80 OTM row per version
- Columns: SPEC_TEMPLATE_ID (= -WF_TEMPLATE_ID), ATR_SEQ (= WF_TEMPLATE_VERSION), FORMULA_ID (= SEQ_FORMULA_ID), FORMULA (= T_FORMULA.FUNCTION_DECLARATION), CREATED_BY, UPDATED_BY

**SOBJTYPE_FORMULA_PARAMS** — parameters for SOBJTYPE_FORMULA_HDR rows
- Columns: SPEC_TEMPLATE_ID, ATR_SEQ, FORMULA_ID, PARAM_ID, PARAM_TYPE, PARAM_NAME, PARAM_STR, PARAM_DISP_STR, PARAM_ATR_SEQ, PARAM_FLD_QRY_FLG, PARAM_QRY_VAL_FLG, CREATED_BY, UPDATED_BY

**T_FORMULA** — reference table (NOT cloned, NOT versioned)
- Columns: SPEC_ID, FUNCTION_NAME, FUNCTION_DECLARATION, FUNCTION_EXP, FUNCTION_DESC
- Used to validate user-defined formula names and retrieve FUNCTION_DECLARATION for SOBJTYPE_FORMULA_HDR

**T_FORMULAPARAMETER** — reference table (NOT cloned)
- Columns: SPEC_ID, PAR_SPEC_ID (=T_FORMULA.SPEC_ID), PARAMETER_NAME, PARAMETER_DESC, PARAMETER_POS (1-indexed)

**REP_TEMPLATE_HDR** — report definitions
- Columns include: REP_TEMPLATE_ID, REP_NAME
- TASK.FILTER_OBJECT for TASK_TYPE 22 = REP_TEMPLATE_HDR.REP_TEMPLATE_ID

**Additional tables cloned verbatim (WF_TEMPLATE_ID + VERSION substituted):**
- GUI_INSTANCE_WF_MAP: WF_TEMPLATE_ID, WF_TEMPLATE_VERSION, MAP_ID, GUI_ID, SPEC_TEMPLATE_ID, TAB_NAME, SECTION_NAME, FIELD_NAME, PROPERTY_TYPE, PROPERTY_VALUE, MAP_TYPE, TASK_ID, ACTION_ID, TASK_MAP_DATA
- TASK_REMINDER: REMINDER_ID, TASK_ID, SUBSCRIBER_ID, DAYS, HOURS, MINUTES, REMINDER_DATETIME, CREATED_BY, TASK_DUE_DATETIME, REMINDER_TYPE, ACTIVE_FLAG, NOTE_ID, WF_TEMPLATE_ID, WF_TEMPLATE_VERSION
- NOTE: NOTE_ID, POSITION, TEXT, PROJECT_ID, OBJECT_ID, OBJECT_TYPE_ID, NOTETYPE, REVISION, AUTHOR_ID, CREATE_DATE, UPDATED_BY, UPDATED_DATE, WF_TEMPLATE_ID, WF_TEMPLATE_VERSION
- ATTACHMENT: ATTACHMENT_ID, ATTACH_SIZE, NAME, LOCATION, PROJECT_ID, OBJECT_ID, OBJECT_TYPE_ID, ATTACHMENT_TYPE, REVISION, AUTHOR_ID, CREATE_DATE, UPDATED_BY, UPDATED_DATE, WF_TEMPLATE_ID, WF_TEMPLATE_VERSION
- WF_PARAMS_MAP: PARAMS_MAP_ID, WF_TEMPLATE_ID, WF_TEMPLATE_VERSION, PARAM_ID, TYPE, VAR_ID, VAR_MODULE_ID, VAR_BO_ID, NAME

### 1.2 TASK_TYPE Reference

Call `getTririgaWFTaskTypeToNameMapping` at session start. Store full mapping.

| TASK_TYPE | Name | EXPRESSION | OTM | FILTER_OBJECT | Notes |
|---|---|---|---|---|---|
| 1 | Start | YES (always) | NO | -1 | TASK_ID=0, PARENT_STEP_ID=-1. New EXPRESSION_SEQ every version |
| 2 | User Action | NO | NO (bare) | -1 | |
| 3 | system- | NO | NO (assumed bare) | -1 | Legacy — unconfirmed |
| 4 | Approval | NO | NO (bare) | -1 | |
| 5 | message- | NO | NO (assumed bare) | -1 | Legacy — unconfirmed |
| 6 | reserve- | NO | NO (assumed bare) | -1 | Legacy — unconfirmed |
| 7 | flow- | NO | NO (assumed bare) | -1 | Legacy — unconfirmed |
| 8 | exception- | NO | NO (assumed bare) | -1 | Legacy — unconfirmed |
| 9 | End | NO | NO (bare) | -1 | Terminal. Null TASK_LABEL. |
| 10 | End of Fork | NO | NO (bare) | -1 | |
| 11 | End of Iteration | NO | NO (bare) | -1 | |
| 12 | End of Switch & Loop | NO | NO (bare) | -1 | Multiple WF_TEMPLATE_STEP rows per convergence branch |
| 13 | Fork | NO | NO (bare) | -1 | |
| 14 | Switch | YES (always) | NO | -1 | Has EVENT_ACTION branch direction. New EXPRESSION_SEQ every version |
| 15 | case- | NO | NO (assumed bare) | -1 | Legacy — unconfirmed |
| 16 | child workflow- | NO | NO (assumed bare) | -1 | Legacy — unconfirmed |
| 17 | Schedule | NO | NO (bare) | -1 | |
| 18 | No-op | NO | NO (bare) | -1 | |
| 19 | Stop | NO | NO (bare) | -1 | Terminal |
| 20 | Loop | NO | NO (bare) | -1 | |
| 21 | Break | YES (always) | NO | -1 | Formula defines break condition. New EXPRESSION_SEQ every version |
| 22 | Query | MAYBE | Mixed | REP_TEMPLATE_HDR.REP_TEMPLATE_ID | See Rule 1.14 |
| 23 | Modify Metadata | NO | NO (bare) | -1 | |
| 24 | Iterator | NO | NO (bare) | -1 | |
| 25 | Get Temp Record | NO | NO (bare) | -1 | |
| 26 | Save Temp to Perm | NO | NO (bare) | -1 | |
| 27 | Create Record | NO | Mixed | -1 | MAP_TYPE 6 flag applies here only |
| 28 | Modify Records | NO | Mixed | -1 | |
| 29 | Retrieve Records | NO | Mixed | See Rule 1.13 | |
| 30 | Associate Records | NO | Mixed | -1 | |
| 31 | Trigger Action | NO | Mixed | See Rule 1.12 | |
| 32 | Delete Reference | NO | NO (bare) | -1 | |
| 33 | Add Child | NO | NO (bare) | -1 | |
| 34 | Set Project | NO | NO (bare) | -1 | |
| 35 | Attach Format File | NO | NO (bare) | -1 | |
| 36 | Populate File | NO | NO (bare) | -1 | |
| 37 | Distill File | NO | NO (bare) | -1 | |
| 38 | Call Workflow | NO | NO (bare) | WF_TEMPLATE_ID of called WF | See Rule 1.11 |
| 39 | Custom | NO | NO (bare) | -1 | |
| 40 | Variable Definition | MAYBE (if MAP_ID>0) | Mixed | -1 | New EXPRESSION_SEQ every version if has EXPRESSION |
| 41 | Variable Assignment | MAYBE (if MAP_ID>0) | Mixed | -1 | New EXPRESSION_SEQ every version if has EXPRESSION |
| 42 | DataConnect | NO | NO (bare) | -1 | |
| 43 | Fact Condition | MAYBE (if MAP_ID>0) | Mixed | -1 | New EXPRESSION_SEQ every version if has EXPRESSION |

EXPRESSION-bearing task types for copyWFCondition query: 1, 14, 21, AND (40 if MAP_ID>0), (41 if MAP_ID>0), (43 if MAP_ID>0)

### 1.3 Schema Rules

- R1.1: Never SELECT * on WF_TEMPLATE (BLOB column)
- R1.2: All WF_TEMPLATE_STEP and TASK queries must filter by both WF_TEMPLATE_ID and WF_TEMPLATE_VERSION/VERSION
- R1.3: TASK.MAP_ID = -1 is null sentinel (not SQL NULL)
- R1.4: TASK.PARAMS_MAP_ID non-nullable; always -1 unless evidence otherwise
- R1.5: WF_TEMPLATE_STEP.STEP_TYPE must equal TASK.TASK_TYPE for same step
- R1.6: TASK.EVENT_ACTION meaning depends on TASK_TYPE:
  - Type 1 (Start): trigger event name e.g. "Pre-Create"
  - Type 14 (Switch): branch direction e.g. "0=false;1=true;" or "0=true;1=false;"
  - Type 28 (Modify Records): write mode e.g. "Append"
  - Type 29 (Retrieve Records): retrieval mode e.g. "GETLIST"
  - Type 31 (Trigger Action): action name e.g. "triSave"
  - Clone verbatim always. Query existing same-TASK_TYPE records to identify valid values for new steps.
- R1.7: TASK_LABEL = short display name (nullable). DESCRIPTION = free-text comment (nullable). Clone verbatim. Collect from user for new steps.
- R1.8: During discovery always read TASK_LABEL and DESCRIPTION for every step.
- R1.9: TASK_FILTER table stores all task filters. FILTER_* columns on TASK are NOT used for filtering EXCEPT FILTER_OBJECT (see Rules 1.10–1.14).
- R1.10: TASK.FILTER_OBJECT uses -1 as null sentinel. ALWAYS clone verbatim for every TASK_TYPE. New entry behavior per TASK_TYPE:

| TASK_TYPE | FILTER_OBJECT meaning | New entry |
|---|---|---|
| 22 | REP_TEMPLATE_HDR.REP_TEMPLATE_ID | Require from user; validate against REP_TEMPLATE_HDR |
| 29 | Unknown (Open Item #11) | Set -1 |
| 31 | TASK_ID of another step in same WF version (user-context step) | Ask user; -1 if none |
| 38 | WF_TEMPLATE_ID of sub-workflow | Require from user; validate STATUS_ID=10 |
| All others | No functional meaning | Set -1 |

- R1.11: TASK_TYPE 38 FILTER_OBJECT = WF_TEMPLATE_ID of called workflow. Clone verbatim. When modifying target, validate STATUS_ID=10.
- R1.12: TASK_TYPE 31 FILTER_OBJECT when not -1 = TASK_ID of another step in same WF+VERSION. Means "run this event as user from that task's result." Referenced task is NOT required to be TASK_TYPE 40. Clone verbatim. For new: ask user. Display referenced task's TASK_LABEL+TASK_TYPE during discovery.
- R1.13: TASK_TYPE 29 FILTER_OBJECT meaning not yet determined. Clone verbatim. New entries = -1.
- R1.14: TASK_TYPE 22 FILTER_OBJECT = REP_TEMPLATE_HDR.REP_TEMPLATE_ID (confirmed across 3319 instances). Clone verbatim. New entries require user input + validation. During discovery join to REP_TEMPLATE_HDR and show REP_NAME.

---

## SECTION 2 — KEY RELATIONSHIPS & ID REUSE

```
WF_TEMPLATE (WF_TEMPLATE_ID, VERSION)
  └── WF_TEMPLATE_STEP: one row per (WF_TEMPLATE_ID, VERSION, STEP_ID)
        │    End Switch: multiple rows per STEP_ID, one per converging branch
        └── TASK: one row per (WF_TEMPLATE_ID, VERSION, TASK_ID)
              ├── OBJECT_TYPE_MAP: rows per (MAP_ID, WF_TEMPLATE_ID, VERSION) — non-bare, non-EXPRESSION-only types
              ├── EXPRESSION: one row per MAP_ID — EXPRESSION-bearing types only
              │     └── EXPRESSION_PARAM: zero or more rows per EXPRESSION
              ├── TASK_FILTER: zero or more rows per task
              └── WF_STEP_REF: zero or more rows per step
```

- STEP_ID = TASK_ID (same value always)
- STEP_ID/TASK_ID reused across versions AND across different workflows — only meaningful within (WF_TEMPLATE_ID, VERSION)
- Start task: TASK_ID=0, PARENT_STEP_ID=-1
- End Switch: one TASK row, multiple WF_TEMPLATE_STEP rows
- R2.1: Always scope queries by both WF_TEMPLATE_ID and VERSION
- R2.2: Cloning to new version: use existing STEP_ID/TASK_ID values with new version (SELECT/INSERT)
- R2.3: EXPRESSION MAP_IDs: every EXPRESSION-bearing task gets a NEW MAP_ID from EXPRESSION_SEQ every version. This includes Start task — no special-casing needed.
- R2.4: Cross-template MAP_ID references are a data integrity violation
- R2.5: Zero OTM rows is valid if all steps are bare or EXPRESSION-only

---

## SECTION 3 — STATUS & VERSION MANAGEMENT

| STATUS_ID | Meaning |
|---|---|
| 10 | Published (live) |
| 20 | Revision In Progress |
| 25 | Retired |

- R3.1: Only STATUS_ID=10 records are active. All discovery/cloning queries filter to STATUS_ID=10.
- R3.2: New WF_TEMPLATE rows inserted with STATUS_ID=20. Never set 10 until validation passes and user confirms publication.
- R3.3: Never modify or delete STATUS_ID=25 records (immutable).
- R3.4: If STATUS_ID=20 exists for target WF_TEMPLATE_ID, HALT and alert user. Resolve before starting new revision.
- R3.5: WF_TEMPLATE_VERSION never sequence-driven. New workflow: VERSION=0. New version: MAX(WF_TEMPLATE_VERSION) WHERE STATUS_ID=10, add 1. Always query fresh.
- R3.6: Version number once written is immutable. If abandoned, re-query max and recompute.

---

## SECTION 4 — SEQUENCES

| Sequence | Drives | Called When |
|---|---|---|
| SEQ_WF_TEMPLATE_ID | WF_TEMPLATE_ID | New workflow only (Path A) |
| SEQ_MAP_ID (alias SEQ_OBJECT_TYPE_MAP_ID) | OTM MAP_ID | New workflow + new steps with OTM only |
| SEQ_TASK_ID | TASK_ID / STEP_ID | New workflow + new steps only |
| SEQ_FORMULA_ID | SOBJTYPE_FORMULA_HDR.FORMULA_ID | Per MAP_TYPE 80 OTM row per clone |
| EXPRESSION_SEQ | EXPRESSION.ID / TASK.MAP_ID | Per EXPRESSION-bearing task per clone |

- R4.1: Never manually assign key values. Always use sequences.
- R4.2: Discover and verify sequence names before first use (see platform queries below).
- R4.3: Call each sequence exactly once per logical record. Store the result immediately. Never call the same sequence twice for the same record.
- R4.4: Never call any sequence for bare steps (MAP_ID=-1).
- R4.5: All sequence calls are issued as individual SQL statements via the MCP SQL tool. The agent reads the returned value and stores it in working memory. That stored literal value is then substituted directly into all subsequent INSERT and UPDATE statements that require it. No placeholder or session variable syntax is used — every generated SQL statement contains fully resolved literal values.

Platform sequence discovery:
- DB2: `SELECT SEQNAME FROM SYSCAT.SEQUENCES WHERE SEQNAME LIKE 'SEQ_%' OR SEQNAME LIKE '%SEQ'`
- Oracle: `SELECT SEQUENCE_NAME FROM USER_SEQUENCES`
- PostgreSQL: `SELECT sequencename FROM pg_sequences`
- SQL Server: `SELECT name FROM sys.sequences`

Platform sequence call (DB2 example — adapt per platform):
```sql
-- DB2: call sequence, read result, store in agent memory as :new_formula_id
SELECT NEXTVAL FOR SEQ_FORMULA_ID FROM SYSIBM.SYSDUMMY1;
-- Agent stores the returned integer. Uses it as a literal in all subsequent statements.
```

---

## SECTION 5 — TASK TYPE & MAP PATTERN RULES

- R5.1: Call getTririgaWFTaskTypeToNameMapping at session start. Store full mapping.
- R5.2: Match user's functional description to TASK_TYPE. Confirm with user. Show candidates if ambiguous.
- R5.3: For Mixed OTM tasks (Section 1.2), query existing tasks of same type in similar WF to determine bare vs mapped pattern before inserting.
- R5.4: OTM row pattern query applies ONLY to non-EXPRESSION-only mapped steps. EXPRESSION-only types (1, 14, 21, and 40/41/43 when EXPRESSION-bearing) have NO OTM rows.
- R5.5: EXPRESSION rows exist for TASK_TYPE 1, 14, 21, and conditionally 40/41/43 (when MAP_ID>0). All other types: no EXPRESSION rows. If found for other types during discovery, flag as unexpected and do not clone.
- R5.6: Before querying OTM for any MAP_ID: first COUNT(*) to get expected row count. Then fetch with FETCH FIRST (count+10) ROWS ONLY. Never rely on unbound queries.

---

## SECTION 6 — MAP_TYPE & FIELD VALUE RULES

| MAP_TYPE | Name | SRC_MEMBER_ID | FIELD_VALUE | Notes |
|---|---|---|---|---|
| 5 | Association String | varies | assoc string | Runtime read only |
| 6 | Use Source Project | -1 | "yes" | TASK_TYPE 27 only. Clone verbatim. Do NOT create for new workflows. |
| 7 | Include Child | varies | varies | Runtime read only |
| 10 | Field Copy | source field ID | NULL | Field-to-field copy |
| 20 | Smart Section | section member ID | NULL | BO-to-BO relationship via SOBJTYPE_SECTIONS. *_OBJECT_TYPE_ID matches SMART_OBJ_TYPE.SPEC_TEMPLATE_ID |
| 30 | Object-to-Section | BO type ID | NULL | Meaning not fully confirmed (Open Item #8) |
| 40 | Literal Set | 0 (sentinel) | literal value | Set field to literal |
| 50 | Runtime Record ID | varies | varies | RUNTIME ONLY — never in published WF. Do not clone or create. |
| 60 | Runtime Task Result | 1=sum, 2=count, 3=list | varies | RUNTIME ONLY — never in published WF. Do not clone or create. |
| 70 | Source | varies | NULL | Lookup via OBJECT_FIELD_MAP. TARGET_OBJECT_TYPE_ID=OBJECT_FIELD_MAP.BO_ID, TARGET_MEMBER_ID=OBJECT_FIELD_MAP.ATR_SEQ, REF_OBJECT_COLUMN=app column for resolved ID. Clone verbatim. |
| 80 | User Defined Formula | SEQ_FORMULA_ID result | function name | SRC_MEMBER_ID = SOBJTYPE_FORMULA_HDR.FORMULA_ID. See Rule 6.4. |
| 90 | Association | -1 (sentinel) | association name | e.g. "Has Document", "Has Comment" |

- R6.1: MAP_TYPE 40: SRC_MEMBER_ID=0, literal in FIELD_VALUE
- R6.2: MAP_TYPE 90: SRC_MEMBER_ID=-1, association name in FIELD_VALUE
- R6.3: MAP_TYPE 10: FIELD_VALUE is NULL
- R6.4: MAP_TYPE 80 clone sequence — execute each step as a discrete SQL statement via MCP SQL tool:
  1. INSERT OTM verbatim (SRC_MEMBER_ID still carries old value from source)
  2. For each MAP_TYPE 80 row: call `SELECT NEXTVAL FOR SEQ_FORMULA_ID FROM SYSIBM.SYSDUMMY1` — read and store returned value as new_formula_id
  3. `INSERT INTO SOBJTYPE_FORMULA_HDR (SPEC_TEMPLATE_ID, ATR_SEQ, FORMULA_ID, FORMULA, CREATED_BY, UPDATED_BY) SELECT -<target_wft_id>, <new_version>, <new_formula_id>, FORMULA, CREATED_BY, UPDATED_BY FROM SOBJTYPE_FORMULA_HDR WHERE SPEC_TEMPLATE_ID=<-src_wft_id> AND ATR_SEQ=<current_max> AND FORMULA_ID=<old_formula_id>` — all angle-bracket values are resolved literals
  4. `INSERT INTO SOBJTYPE_FORMULA_PARAMS (SPEC_TEMPLATE_ID, ATR_SEQ, FORMULA_ID, PARAM_ID, PARAM_TYPE, PARAM_NAME, PARAM_STR, PARAM_DISP_STR, PARAM_ATR_SEQ, PARAM_FLD_QRY_FLG, PARAM_QRY_VAL_FLG, CREATED_BY, UPDATED_BY) SELECT -<target_wft_id>, <new_version>, <new_formula_id>, PARAM_ID, PARAM_TYPE, PARAM_NAME, PARAM_STR, PARAM_DISP_STR, PARAM_ATR_SEQ, PARAM_FLD_QRY_FLG, PARAM_QRY_VAL_FLG, CREATED_BY, UPDATED_BY FROM SOBJTYPE_FORMULA_PARAMS WHERE SPEC_TEMPLATE_ID=<-src_wft_id> AND ATR_SEQ=<current_max> AND FORMULA_ID=<old_formula_id>`
  5. `UPDATE OBJECT_TYPE_MAP SET SRC_MEMBER_ID=<new_formula_id> WHERE WF_TEMPLATE_ID=<target_wft_id> AND WF_TEMPLATE_VERSION=<new_version> AND MAP_TYPE=80 AND MAP_ID=<map_id> AND SRC_MEMBER_ID=<old_formula_id>`
- R6.5: MAP_TYPE 70: clone OTM verbatim. Do not resolve or rewrite OBJECT_FIELD_MAP reference.
- R6.6: MAP_TYPE 20: clone all rows verbatim.
- R6.7: MAP_TYPE 6: clone verbatim for existing workflows. Do NOT generate for new workflows.

---

## SECTION 7 — STRUCTURAL RULES

- R7.1: Every workflow must have exactly one Start task (TASK_TYPE 1, TASK_ID=0, PARENT_STEP_ID=-1)
- R7.2: Last step on main path must be End task (TASK_TYPE 9)
- R7.3: Every branch must terminate with Stop/End or converge at End Switch
- R7.4: Terminal types (Stop=19, End=9) can never be PARENT_STEP_ID for any step
- R7.5: Root sentinels confirmed: Start task PARENT_STEP_ID=-1, Start task STEP_ID=0
- R7.6: When removing terminal step, alert user. Confirm replacement terminal exists.
- R7.7: Start task MAP_ID points to EXPRESSION record. No OTM rows. New EXPRESSION_SEQ value is generated for the Start task on every new version, identical to all other EXPRESSION-bearing tasks — no special handling needed.
- R7.8: Blank (single space ' ') FORMULA on EXPRESSION row is valid. Satisfies V8. V10 passes automatically (no pN tokens). Clone verbatim. Do not substitute a default.

---

## SECTION 8 — SWITCH TASK RULES

### 8.1 Structure
- R8.1: Every Switch (TASK_TYPE 14) has exactly two direct children in WF_TEMPLATE_STEP and its own dedicated End Switch step (TASK_TYPE 12). End Switch steps never shared between Switch tasks.
- R8.2: End Switch has one TASK row but multiple WF_TEMPLATE_STEP rows — one per converging branch with different PARENT_STEP_ID.
- R8.3: End Switch step may never be direct child of another End Switch step.
- R8.4: Switch tasks may be nested arbitrarily deep. Process inner before outer.

### 8.2 Branch Direction
- R8.5: TASK.EVENT_ACTION defines branch direction:
  - "0=false;1=true;" → index 0=FALSE branch, index 1=TRUE branch
  - "0=true;1=false;" → index 0=TRUE branch, index 1=FALSE branch
- R8.6: Clone EVENT_ACTION verbatim. New: present both options, require user confirmation.

### 8.3 Expression Rules
- R8.7: Switch tasks require EXPRESSION row (ID=MAP_ID). Confirmed formula patterns:
  - Constant: `"1 == 0"` — no pN tokens, zero EXPRESSION_PARAM rows
  - Field comparison: `p0 == "B"` — one pN token, one EXPRESSION_PARAM row
  - Two-field: `p0 == p1` — two pN tokens, two EXPRESSION_PARAM rows
  - Named system function: `startsWith(p0, "Active")` — one pN token, one row
  - User Defined function: `AddOne(A)` — validate against T_FORMULA (see R8.16)
- R8.8: EXPRESSION_PARAM rows only required when FORMULA contains pN tokens. PARAM_ID is zero-indexed, scoped independently per task.
- R8.9: PARAM_STR XML — two confirmed variants:
  - type='field': `<task type='field' id='{TASK_ID}'><field fieldName='{F}' sectionName='{S}' boId='{B}' /></task>`
  - type='item': `<task type='item' id='{TASK_ID}' item='Result Count' />`
  - id = TASK_ID of step providing value (0 = triggering record / Start task context)
- R8.10: Never construct PARAM_STR from scratch. Base on observed values from existing Switch tasks for same BO type.
- R8.11: After writing any FORMULA, validate all pN tokens against EXPRESSION_PARAM rows (V10).
- R8.12: If parameters added/removed, re-index all PARAM_IDs from zero.

### 8.4 Switch Task Clone Rules
- R8.13: Switch tasks are handled identically to Start/Break in the clone sequence — call EXPRESSION_SEQ once, copy EXPRESSION + EXPRESSION_PARAM rows, update TASK.MAP_ID with the new literal value. No special Switch-specific logic needed beyond this.
- R8.14: When modifying a Switch task, display current FORMULA, all EXPRESSION_PARAM rows, and EVENT_ACTION. Confirm changes before inserting.
- R8.15: After copying EXPRESSION+EXPRESSION_PARAM (any method), run V10 before proceeding.

### 8.5 Formula Types
- R8.16: Two formula categories:
  1. System formulas — built-in (==, <=, startsWith, etc.). Not in database. Cannot validate against DB.
  2. User Defined — in T_FORMULA. When creating new:
     - Ask user for formula name
     - If not recognized system function: `SELECT SPEC_ID, FUNCTION_NAME, FUNCTION_DECLARATION, FUNCTION_DESC FROM T_FORMULA WHERE FUNCTION_NAME = '<name>'`
     - If found: present FUNCTION_DECLARATION for confirmation, collect pN params using T_FORMULAPARAMETER.PARAMETER_POS order
     - If not found: HALT — do not insert unrecognized formula
  - When cloning: carry EXPRESSION.FORMULA and EXPRESSION_PARAM verbatim. Do not re-validate formula names.
  - Always run V10 after any EXPRESSION copy.

---

## SECTION 9 — WF_STEP_REF RULES

- R9.1: WF_STEP_REF is 0..N. Multiple rows per step distinguished by USAGE_TYPE. Clone ALL rows.
- R9.2: USAGE_TYPE 1 = primary source. USAGE_TYPE 2 = secondary reference.
- R9.3: Unmodified steps: clone all rows verbatim (SELECT/INSERT with new VERSION).
- R9.4: Modified steps: display all rows grouped by USAGE_TYPE. Confirm: clone/update/drop each.
- R9.5: REF_TASK_ID references another step's TASK_ID. Remains valid across versions (TASK_IDs reused).
- R9.6: Before removing any step, check if any WF_STEP_REF.REF_TASK_ID points to it. Alert user if so.

---

## SECTION 10 — TASK_FILTER RULES

- R10.1: Keyed by (TASK_ID, WF_TEMPLATE_ID, WF_TEMPLATE_VERSION, SEQUENCE_ID). FILTER_* columns on TASK are not used.
- R10.2: Multiple filter rows per task: AND logic between rows. SEQUENCE_ID starts at 1, increments manually.
- R10.3: Applies at minimum to TASK_TYPE 22 and 29. Query for all steps during discovery.
- R10.4: Filter semantics:
  - LEFT_SECTION_NAME + LEFT_FIELD_NAME: field being evaluated (NULL for whole-record list filters)
  - LEFT_DATA_TYPE: 320=text confirmed; -1 when no field
  - OPERATOR: see R10.5
  - CONSTANT_FLAG: 1=right side literal; 0=right side field reference
  - RIGHT_VALUE: literal when CONSTANT_FLAG=1
  - ASSOCIATED_TO_TASK: TASK_ID of related task (0=none)
- R10.5: OPERATOR values:
  - 10=Equals (confirmed), 11=Not Equals (inferred), 12=Less Than (inferred), 13=Greater Than (inferred)
  - 14=Less Than or Equal (inferred), 15=Greater Than or Equal (inferred)
  - 16=Contains string (confirmed), 17=Does Not Contain (inferred)
  - 20–21=UNKNOWN (Open Item #2), 22=Is In List (confirmed), 23=Is Not In List (confirmed)
  - 30–35=UNKNOWN (Open Item #2)
- R10.6: OPERATOR 22/23 with both field names null = whole-record list comparison. LEFT_DATA_TYPE=-1, CONSTANT_FLAG=0, both LIST_FLAG=1.
- R10.7: When creating/modifying filter: query existing TASK_FILTER for same TASK_TYPE+field to identify correct data types and operators.
- R10.8: When creating/modifying Query/Retrieve Records task: ask user whether filter applies.
- R10.9: SEQUENCE_ID is manual integer. Start at 1.
- R10.10: OPERATOR 20, 21, 30–35 not confirmed. Flag when encountered. (Open Item #2)

---

## SECTION 11 — STEP REMOVAL & RE-PARENTING

- R11.1: No inactive flag. Removal = omission (before insert) or deletion (STATUS_ID=20 records only).
- R11.2: Only permitted DELETE is for STATUS_ID=20 in-progress records.
- R11.3: Before removing step: capture its PARENT_STEP_ID. Re-parent all direct children to that value.
- R11.4: Re-parenting applies to direct children only.
- R11.5: Re-parent BEFORE deleting. Verify: `SELECT STEP_ID FROM WF_TEMPLATE_STEP WHERE PARENT_STEP_ID = <removed_step_id> AND WF_TEMPLATE_ID=<tid> AND WF_TEMPLATE_VERSION=<ver>` → expect 0 rows.
- R11.6: If removed step's parent is terminal type: alert user, request valid alternative.
- R11.7: When removing Switch task: also remove both branch children and End Switch step (all convergence rows).
- R11.8: Deletion order for any removed step — each issued as a discrete SQL statement:
  ```sql
  -- 0. Remove task filters
  DELETE FROM TASK_FILTER WHERE TASK_ID=<id> AND WF_TEMPLATE_ID=<tid> AND WF_TEMPLATE_VERSION=<ver>;
  -- 1. Remove expression params (EXPRESSION-bearing types only)
  DELETE FROM EXPRESSION_PARAM WHERE FORMULA_ID=<map_id>;
  -- 2. Remove expression (EXPRESSION-bearing types only)
  DELETE FROM EXPRESSION WHERE ID=<map_id>;
  -- 3. Remove formula params (MAP_TYPE 80 only)
  DELETE FROM SOBJTYPE_FORMULA_PARAMS WHERE SPEC_TEMPLATE_ID=<-tid> AND ATR_SEQ=<ver> AND FORMULA_ID=<src_member_id>;
  -- 4. Remove formula header (MAP_TYPE 80 only)
  DELETE FROM SOBJTYPE_FORMULA_HDR WHERE SPEC_TEMPLATE_ID=<-tid> AND ATR_SEQ=<ver> AND FORMULA_ID=<src_member_id>;
  -- 5. Remove OTM rows
  DELETE FROM OBJECT_TYPE_MAP WHERE MAP_ID=<map_id> AND WF_TEMPLATE_ID=<tid> AND WF_TEMPLATE_VERSION=<ver>;
  -- 6. Remove task
  DELETE FROM TASK WHERE TASK_ID=<id> AND WF_TEMPLATE_ID=<tid> AND VERSION=<ver>;
  -- 7. Remove step
  DELETE FROM WF_TEMPLATE_STEP WHERE STEP_ID=<id> AND WF_TEMPLATE_ID=<tid> AND WF_TEMPLATE_VERSION=<ver>;
  -- 8. Remove step refs
  DELETE FROM WF_STEP_REF WHERE STEP_ID=<id> AND WF_TEMPLATE_ID=<tid> AND WF_TEMPLATE_VERSION=<ver>;
  ```

---

## SECTION 12 — SYSTEM FIELDS & SCHEMA INSPECTION

- R12.1: No fields are auto-populated via the MCP SQL tool. All fields including timestamps and user IDs must be set explicitly as literal values in each SQL statement.
- R12.2: Before first INSERT, query non-nullable columns with no default for each target table.
- R12.3: For unknown required fields, query an existing record of the same type as reference. Never fabricate.

---

## SECTION 13 — VALIDATION

All checks must pass before publication. Any failure is blocking. Run in sequence:

```
1. Branch tracing (Section 14) — must complete before SQL checks
2. V1  — every step has matching task
3. V2  — all mapped tasks (non-EXPRESSION-only) have valid OTM rows
4. V3  — no orphaned child steps
5. V4  — no orphaned OBJECT_TYPE_MAP rows
6. V5  — exactly one Start task
7. V6  — last step is End task
8. V7  — every Switch task has exactly 2 children
9. V8  — every EXPRESSION-bearing task has EXPRESSION record
10. V10 — every pN token in every EXPRESSION-bearing FORMULA has matching EXPRESSION_PARAM row
11. V11 — no orphaned TASK_FILTER rows
```

```sql
-- V1: Every step has matching task
SELECT ts.STEP_ID FROM WF_TEMPLATE_STEP ts
LEFT JOIN TASK tsk ON tsk.TASK_ID=ts.STEP_ID AND tsk.WF_TEMPLATE_ID=ts.WF_TEMPLATE_ID AND tsk.VERSION=ts.WF_TEMPLATE_VERSION
WHERE ts.WF_TEMPLATE_ID=<id> AND ts.WF_TEMPLATE_VERSION=<ver> AND tsk.TASK_ID IS NULL;
-- Expected: 0 rows

-- V2: All mapped tasks (non-EXPRESSION-only) have valid OTM rows
SELECT tsk.TASK_ID, tsk.MAP_ID FROM TASK tsk
LEFT JOIN OBJECT_TYPE_MAP otm ON otm.MAP_ID=tsk.MAP_ID AND otm.WF_TEMPLATE_ID=tsk.WF_TEMPLATE_ID AND otm.WF_TEMPLATE_VERSION=tsk.VERSION
WHERE tsk.WF_TEMPLATE_ID=<id> AND tsk.VERSION=<ver>
  AND tsk.MAP_ID != -1
  AND tsk.TASK_TYPE NOT IN (1, 14, 21)
  AND NOT (tsk.TASK_TYPE IN (40, 41, 43) AND tsk.MAP_ID > 0)
  AND otm.MAP_ID IS NULL;
-- Expected: 0 rows

-- V3: No orphaned child steps (excludes root sentinels)
SELECT ts.STEP_ID, ts.PARENT_STEP_ID FROM WF_TEMPLATE_STEP ts
LEFT JOIN WF_TEMPLATE_STEP parent ON parent.STEP_ID=ts.PARENT_STEP_ID AND parent.WF_TEMPLATE_ID=ts.WF_TEMPLATE_ID AND parent.WF_TEMPLATE_VERSION=ts.WF_TEMPLATE_VERSION
WHERE ts.WF_TEMPLATE_ID=<id> AND ts.WF_TEMPLATE_VERSION=<ver>
  AND ts.PARENT_STEP_ID NOT IN (-1, 0) AND parent.STEP_ID IS NULL;
-- Expected: 0 rows

-- V4: No orphaned OTM rows (0 OTM rows total is also valid per R2.5)
SELECT otm.MAP_ID FROM OBJECT_TYPE_MAP otm
WHERE otm.WF_TEMPLATE_ID=<id> AND otm.WF_TEMPLATE_VERSION=<ver>
  AND otm.MAP_ID NOT IN (SELECT MAP_ID FROM TASK WHERE WF_TEMPLATE_ID=<id> AND VERSION=<ver> AND MAP_ID != -1);
-- Expected: 0 rows

-- V5: Exactly one Start task
SELECT COUNT(*) AS CNT FROM WF_TEMPLATE_STEP ts
JOIN TASK tsk ON tsk.TASK_ID=ts.STEP_ID AND tsk.WF_TEMPLATE_ID=ts.WF_TEMPLATE_ID AND tsk.VERSION=ts.WF_TEMPLATE_VERSION
WHERE ts.WF_TEMPLATE_ID=<id> AND ts.WF_TEMPLATE_VERSION=<ver> AND tsk.TASK_TYPE=1;
-- Expected: 1

-- V6: Last step on main path is End task
SELECT ts.STEP_ID, tsk.TASK_TYPE FROM WF_TEMPLATE_STEP ts
JOIN TASK tsk ON tsk.TASK_ID=ts.STEP_ID AND tsk.WF_TEMPLATE_ID=ts.WF_TEMPLATE_ID AND tsk.VERSION=ts.WF_TEMPLATE_VERSION
WHERE ts.WF_TEMPLATE_ID=<id> AND ts.WF_TEMPLATE_VERSION=<ver>
  AND ts.STEP_ID NOT IN (SELECT DISTINCT PARENT_STEP_ID FROM WF_TEMPLATE_STEP WHERE WF_TEMPLATE_ID=<id> AND WF_TEMPLATE_VERSION=<ver> AND PARENT_STEP_ID NOT IN (-1, 0))
  AND tsk.TASK_TYPE != 9;
-- Expected: 0 rows

-- V7: Every Switch task has exactly 2 direct children
SELECT ts.STEP_ID, COUNT(child.STEP_ID) AS CHILD_COUNT
FROM WF_TEMPLATE_STEP ts
JOIN TASK tsk ON tsk.TASK_ID=ts.STEP_ID AND tsk.WF_TEMPLATE_ID=ts.WF_TEMPLATE_ID AND tsk.VERSION=ts.WF_TEMPLATE_VERSION
LEFT JOIN WF_TEMPLATE_STEP child ON child.PARENT_STEP_ID=ts.STEP_ID AND child.WF_TEMPLATE_ID=ts.WF_TEMPLATE_ID AND child.WF_TEMPLATE_VERSION=ts.WF_TEMPLATE_VERSION
WHERE ts.WF_TEMPLATE_ID=<id> AND ts.WF_TEMPLATE_VERSION=<ver> AND tsk.TASK_TYPE=14
GROUP BY ts.STEP_ID HAVING COUNT(child.STEP_ID) != 2;
-- Expected: 0 rows

-- V8: Every EXPRESSION-bearing task has EXPRESSION record
SELECT ts.STEP_ID, tsk.MAP_ID FROM WF_TEMPLATE_STEP ts
JOIN TASK tsk ON tsk.TASK_ID=ts.STEP_ID AND tsk.WF_TEMPLATE_ID=ts.WF_TEMPLATE_ID AND tsk.VERSION=ts.WF_TEMPLATE_VERSION
LEFT JOIN EXPRESSION e ON e.ID=tsk.MAP_ID
WHERE ts.WF_TEMPLATE_ID=<id> AND ts.WF_TEMPLATE_VERSION=<ver>
  AND (tsk.TASK_TYPE IN (1,14,21) OR (tsk.TASK_TYPE IN (40,41,43) AND tsk.MAP_ID > 0))
  AND e.ID IS NULL;
-- Expected: 0 rows

-- V10: Every pN token in FORMULA has matching EXPRESSION_PARAM row
-- Phase 1: retrieve all FORMULA strings for EXPRESSION-bearing tasks
SELECT e.ID AS MAP_ID, e.FORMULA FROM EXPRESSION e
JOIN TASK tsk ON tsk.MAP_ID=e.ID
JOIN WF_TEMPLATE_STEP ts ON ts.STEP_ID=tsk.TASK_ID AND ts.WF_TEMPLATE_ID=tsk.WF_TEMPLATE_ID AND ts.WF_TEMPLATE_VERSION=tsk.VERSION
WHERE ts.WF_TEMPLATE_ID=<id> AND ts.WF_TEMPLATE_VERSION=<ver>
  AND (tsk.TASK_TYPE IN (1,14,21) OR (tsk.TASK_TYPE IN (40,41,43) AND tsk.MAP_ID > 0));
-- Phase 2 (agent logic): parse each FORMULA for pN tokens.
-- For each token pN: SELECT COUNT(*) FROM EXPRESSION_PARAM WHERE FORMULA_ID=<map_id> AND PARAM_ID=N → expect 1
-- Blank/space formula = no pN tokens = passes automatically.

-- V11: No orphaned TASK_FILTER rows
SELECT tf.TASK_ID FROM TASK_FILTER tf
LEFT JOIN TASK tsk ON tsk.TASK_ID=tf.TASK_ID AND tsk.WF_TEMPLATE_ID=tf.WF_TEMPLATE_ID AND tsk.VERSION=tf.WF_TEMPLATE_VERSION
WHERE tf.WF_TEMPLATE_ID=<id> AND tf.WF_TEMPLATE_VERSION=<ver> AND tsk.TASK_ID IS NULL;
-- Expected: 0 rows
```

---

## SECTION 14 — BRANCH TRACING

Run before V1–V11 whenever: Switch task added/modified, step removed from branch, or final validation.

```
Step 1: Find all Switch tasks:
SELECT ts.STEP_ID FROM WF_TEMPLATE_STEP ts
JOIN TASK tsk ON tsk.TASK_ID=ts.STEP_ID AND tsk.WF_TEMPLATE_ID=ts.WF_TEMPLATE_ID AND tsk.VERSION=ts.WF_TEMPLATE_VERSION
WHERE ts.WF_TEMPLATE_ID=<id> AND ts.WF_TEMPLATE_VERSION=<ver> AND tsk.TASK_TYPE=14;

Step 2: For each Switch task, run traceBranch() — innermost first:

FUNCTION traceBranch(<step_id>):
  Get TASK_TYPE of <step_id>
  IF Stop(19) or End(9)     → RETURN valid
  IF End Switch(12)          → RETURN valid
  IF Switch(14):
    Get children where PARENT_STEP_ID = <step_id> → must be exactly 2, else HALT
    traceBranch(child_0); traceBranch(child_1)
    Confirm exactly 1 End Switch in this Switch's lineage
    RETURN valid
  ELSE:
    Get children of <step_id>
    IF 0 children → HALT (unterminated branch)
    FOR each child: traceBranch(child)
```

---

## SECTION 15 — CLONE WORKFLOW (PATH B — New Version of Existing)

### Pre-Flight Checklist
```
1. Detect platform (Section 0)
2. Call getTririgaWFTaskTypeToNameMapping — store full mapping
3. Confirm root sentinel from existing WF (PARENT_STEP_ID=-1 for Start, STEP_ID=0)
4. Discover and verify all sequence names (Section 4)
5. CHECK: SELECT WF_TEMPLATE_ID, WF_TEMPLATE_VERSION, STATUS_ID FROM WF_TEMPLATE
          WHERE WF_TEMPLATE_ID=<id> ORDER BY WF_TEMPLATE_VERSION DESC
   - If STATUS_ID=20 exists → HALT (Rule 3.4)
   - Get MAX(WF_TEMPLATE_VERSION) WHERE STATUS_ID=10 → current_max
   - new_version = current_max + 1
6. Inspect non-nullable columns for any target tables not in standard schema (Rule 12.2)
```

### Discovery Reads (from src_wft_id, current_max)
Read all of the following before generating any SQL:
- WF_TEMPLATE (all columns except BLOB)
- WF_TEMPLATE_STEP
- TASK (all 41 columns including TASK_LABEL, DESCRIPTION, EVENT_ACTION, FILTER_OBJECT, MAP_ID)
- OBJECT_TYPE_MAP — COUNT(*) per MAP_ID first (Rule 5.6), then fetch with N+10 limit
- EXPRESSION + EXPRESSION_PARAM (for all EXPRESSION-bearing tasks)
- SOBJTYPE_FORMULA_HDR + SOBJTYPE_FORMULA_PARAMS (for MAP_TYPE 80 OTM rows)
- TASK_FILTER
- WF_STEP_REF
- GUI_INSTANCE_WF_MAP
- TASK_REMINDER
- NOTE
- ATTACHMENT
- WF_PARAMS_MAP

Present clone summary to user. Wait for confirmation before generating SQL.

### Sequence Pre-Resolution (before any INSERT/UPDATE)

Before issuing any write statements, call all required sequences and store results:

```sql
-- For each EXPRESSION-bearing task (TASK_TYPE 1, 14, 21, and 40/41/43 where MAP_ID>0):
-- Issue one call per task. Read and store each result individually.
-- DB2 example:
SELECT NEXTVAL FOR EXPRESSION_SEQ FROM SYSIBM.SYSDUMMY1;
-- Agent stores result as new_map_id_for_task_<task_id>

-- For each MAP_TYPE 80 OTM row:
-- Issue one call per row. Read and store each result individually.
SELECT NEXTVAL FOR SEQ_FORMULA_ID FROM SYSIBM.SYSDUMMY1;
-- Agent stores result as new_formula_id_for_map_<map_id>
```

All stored values are substituted as resolved literals in every subsequent SQL statement.
No placeholder syntax (`?`, `:param`) appears in any generated write statement.

### Master Clone SQL Sequence

Execute in this exact order. All use SELECT/INSERT pattern with fully resolved literal values.
src_wft_id and target_wft_id are identical for a new version of an existing workflow.

```sql
-- STEP 1: WF_TEMPLATE
-- STATUS_ID=20, timestamps=CURRENT TIMESTAMP, SOURCE_WF_TEMPLATE_ID/VERSION=-1 for REVISE type
INSERT INTO WF_TEMPLATE (WF_TEMPLATE_ID, WF_TEMPLATE_VERSION, BO_CLASS_TYPE_ID, BO_TYPE_NAME,
  BO_TYPE_ID, BO_EVENT_NAME, WF_NAME, DESCRIPTION, STATUS_ID, UPDATED_DATE, UPDATED_BY,
  CREATED_DATE, CREATED_BY, WF_TYPE, SOURCE_WF_TEMPLATE_ID, SOURCE_WF_TEMPLATE_VERSION,
  TEMPLATE_FLAG, BO_ID, START_DATE, END_DATE, SECONDARY_BO_CLASS_TYPE_ID, SECONDARY_BO_TYPE_ID,
  ASSOCIATION_NAME, IGNORE_MODULE_WF_FLAG, LOCK_RECORD_FLAG, PROJECT_ID, PROJECT_NAME,
  INSTANCE_DATA_FLAG, WF_MODIFIER, OBJECT_LABEL_ID)
SELECT <target_wft_id>, <new_version>, BO_CLASS_TYPE_ID, BO_TYPE_NAME, BO_TYPE_ID, BO_EVENT_NAME,
  WF_NAME, DESCRIPTION, 20, CURRENT TIMESTAMP, UPDATED_BY, CURRENT TIMESTAMP, CREATED_BY,
  WF_TYPE, -1, -1, TEMPLATE_FLAG, BO_ID, CURRENT TIMESTAMP, END_DATE,
  SECONDARY_BO_CLASS_TYPE_ID, SECONDARY_BO_TYPE_ID, ASSOCIATION_NAME, IGNORE_MODULE_WF_FLAG,
  LOCK_RECORD_FLAG, PROJECT_ID, PROJECT_NAME, INSTANCE_DATA_FLAG, WF_MODIFIER, OBJECT_LABEL_ID
FROM WF_TEMPLATE WHERE WF_TEMPLATE_ID=<src_wft_id> AND WF_TEMPLATE_VERSION=<current_max>;

-- STEP 2: WF_TEMPLATE_STEP
INSERT INTO WF_TEMPLATE_STEP (WF_TEMPLATE_ID, WF_TEMPLATE_VERSION, STEP_ID, STEP_TYPE, PARENT_STEP_ID, CREATED_DATE, CREATED_BY)
SELECT <target_wft_id>, <new_version>, STEP_ID, STEP_TYPE, PARENT_STEP_ID, CREATED_DATE, CREATED_BY
FROM WF_TEMPLATE_STEP WHERE WF_TEMPLATE_ID=<src_wft_id> AND WF_TEMPLATE_VERSION=<current_max>;

-- STEP 3: OBJECT_TYPE_MAP (ALL rows verbatim — MAP_TYPE 80 SRC_MEMBER_ID patched in Step 4)
INSERT INTO OBJECT_TYPE_MAP (MAP_ID, SRC_OBJECT_TYPE_ID, SRC_TAB_ID, SRC_MEMBER_ID,
  TARGET_OBJECT_TYPE_ID, TARGET_TAB_ID, TARGET_MEMBER_ID, MAP_TYPE, CREATED_DATE, CREATED_BY,
  FIELD_VALUE, WF_TEMPLATE_ID, WF_TEMPLATE_VERSION, SRC_FIELD_VALUE)
SELECT MAP_ID, SRC_OBJECT_TYPE_ID, SRC_TAB_ID, SRC_MEMBER_ID, TARGET_OBJECT_TYPE_ID,
  TARGET_TAB_ID, TARGET_MEMBER_ID, MAP_TYPE, CREATED_DATE, CREATED_BY, FIELD_VALUE,
  <target_wft_id>, <new_version>, SRC_FIELD_VALUE
FROM OBJECT_TYPE_MAP WHERE WF_TEMPLATE_ID=<src_wft_id> AND WF_TEMPLATE_VERSION=<current_max>;

-- STEP 4: cloneFormulaMaps — one pass per MAP_TYPE 80 row
-- Sequence already called above; new_formula_id is a resolved literal.
-- 4a: (already known from discovery) MAP_TYPE 80 rows and their old SRC_MEMBER_ID values

-- 4c: Clone SOBJTYPE_FORMULA_HDR — one statement per MAP_TYPE 80 row
INSERT INTO SOBJTYPE_FORMULA_HDR (SPEC_TEMPLATE_ID, ATR_SEQ, FORMULA_ID, FORMULA, CREATED_BY, UPDATED_BY)
SELECT -<target_wft_id>, <new_version>, <new_formula_id>, FORMULA, CREATED_BY, UPDATED_BY
FROM SOBJTYPE_FORMULA_HDR
WHERE SPEC_TEMPLATE_ID=-<src_wft_id> AND ATR_SEQ=<current_max> AND FORMULA_ID=<old_formula_id>;

-- 4d: Clone SOBJTYPE_FORMULA_PARAMS — one statement per MAP_TYPE 80 row
INSERT INTO SOBJTYPE_FORMULA_PARAMS (SPEC_TEMPLATE_ID, ATR_SEQ, FORMULA_ID, PARAM_ID, PARAM_TYPE,
  PARAM_NAME, PARAM_STR, PARAM_DISP_STR, PARAM_ATR_SEQ, PARAM_FLD_QRY_FLG, PARAM_QRY_VAL_FLG, CREATED_BY, UPDATED_BY)
SELECT -<target_wft_id>, <new_version>, <new_formula_id>, PARAM_ID, PARAM_TYPE, PARAM_NAME,
  PARAM_STR, PARAM_DISP_STR, PARAM_ATR_SEQ, PARAM_FLD_QRY_FLG, PARAM_QRY_VAL_FLG, CREATED_BY, UPDATED_BY
FROM SOBJTYPE_FORMULA_PARAMS
WHERE SPEC_TEMPLATE_ID=-<src_wft_id> AND ATR_SEQ=<current_max> AND FORMULA_ID=<old_formula_id>;

-- 4e: Patch OTM SRC_MEMBER_ID — one statement per MAP_TYPE 80 row
UPDATE OBJECT_TYPE_MAP SET SRC_MEMBER_ID=<new_formula_id>
WHERE WF_TEMPLATE_ID=<target_wft_id> AND WF_TEMPLATE_VERSION=<new_version>
  AND MAP_TYPE=80 AND MAP_ID=<map_id> AND SRC_MEMBER_ID=<old_formula_id>;

-- STEP 5: GUI_INSTANCE_WF_MAP
INSERT INTO GUI_INSTANCE_WF_MAP (WF_TEMPLATE_ID, WF_TEMPLATE_VERSION, MAP_ID, GUI_ID,
  SPEC_TEMPLATE_ID, TAB_NAME, SECTION_NAME, FIELD_NAME, PROPERTY_TYPE, PROPERTY_VALUE,
  MAP_TYPE, TASK_ID, ACTION_ID, TASK_MAP_DATA)
SELECT <target_wft_id>, <new_version>, MAP_ID, GUI_ID, SPEC_TEMPLATE_ID, TAB_NAME, SECTION_NAME,
  FIELD_NAME, PROPERTY_TYPE, PROPERTY_VALUE, MAP_TYPE, TASK_ID, ACTION_ID, TASK_MAP_DATA
FROM GUI_INSTANCE_WF_MAP WHERE WF_TEMPLATE_ID=<src_wft_id> AND WF_TEMPLATE_VERSION=<current_max>;

-- STEP 6: TASK (all rows verbatim — EXPRESSION MAP_IDs patched in Step 7)
INSERT INTO TASK (TASK_ID, WF_TEMPLATE_ID, TASK_LABEL, VERSION, CLASSIFICATION_TYPE_ID,
  OBJECT_TYPE_ID, EVENT_ACTION, DESCRIPTION, ASSIGN_TO_USER, EST_END_IN_DAYS, EST_END_IN_MINS,
  EST_END_IN_HRS, RECURRENCE_ID, MAP_ID, SOURCE_TASK_ID, ASSIGN_TO_FLAG, TARGET_TASK_ID,
  SERVICE_CONTEXT, SERVICE_ASSOC_STRING, FILTER_CLASS, FILTER_OBJECT, FILTER_SECTION,
  FILTER_FIELD, FILTER_OPERATOR, FILTER_VALUE, SUM_SECTION, SUM_FIELD, ASSIGNEE_TASK_ID,
  TARGET_ASSOC_STRING, TASK_TYPE, LOCK_USER, ASSOC_CLASS_TYPE_ID, ASSOC_OBJECT_TYPE_ID,
  DELETE_SECTION, USE_MAP, SORT_COUNT, DATE_CONTEXT, TRANSACTION_TYPE, STATUS, PARAMS_MAP_ID, FORMULA_RECALC)
SELECT TASK_ID, <target_wft_id>, TASK_LABEL, <new_version>, CLASSIFICATION_TYPE_ID,
  OBJECT_TYPE_ID, EVENT_ACTION, DESCRIPTION, ASSIGN_TO_USER, EST_END_IN_DAYS, EST_END_IN_MINS,
  EST_END_IN_HRS, RECURRENCE_ID, MAP_ID, SOURCE_TASK_ID, ASSIGN_TO_FLAG, TARGET_TASK_ID,
  SERVICE_CONTEXT, SERVICE_ASSOC_STRING, FILTER_CLASS, FILTER_OBJECT, FILTER_SECTION,
  FILTER_FIELD, FILTER_OPERATOR, FILTER_VALUE, SUM_SECTION, SUM_FIELD, ASSIGNEE_TASK_ID,
  TARGET_ASSOC_STRING, TASK_TYPE, LOCK_USER, ASSOC_CLASS_TYPE_ID, ASSOC_OBJECT_TYPE_ID,
  DELETE_SECTION, USE_MAP, SORT_COUNT, DATE_CONTEXT, TRANSACTION_TYPE, STATUS, PARAMS_MAP_ID, FORMULA_RECALC
FROM TASK WHERE WF_TEMPLATE_ID=<src_wft_id> AND VERSION=<current_max>;

-- STEP 7: Clone EXPRESSION + EXPRESSION_PARAM and patch TASK.MAP_ID
-- Identify EXPRESSION-bearing tasks:
SELECT MAP_ID, TASK_ID FROM TASK
WHERE WF_TEMPLATE_ID=<target_wft_id> AND VERSION=<new_version>
  AND (TASK_TYPE=1 OR TASK_TYPE=14 OR TASK_TYPE=21
    OR (TASK_TYPE=40 AND MAP_ID > 0)
    OR (TASK_TYPE=41 AND MAP_ID > 0)
    OR (TASK_TYPE=43 AND MAP_ID > 0));
-- For EACH row returned (new_map_id was pre-resolved above per task):

-- 7b-ii: Clone EXPRESSION — one statement per EXPRESSION-bearing task
INSERT INTO EXPRESSION (FORMULA, ID)
SELECT FORMULA, <new_map_id>
FROM EXPRESSION WHERE ID=<old_map_id>;

-- 7b-iii: Clone EXPRESSION_PARAM — one statement per EXPRESSION-bearing task
INSERT INTO EXPRESSION_PARAM (FORMULA_ID, PARAM_ID, PARAM_STR)
SELECT <new_map_id>, PARAM_ID, PARAM_STR
FROM EXPRESSION_PARAM WHERE FORMULA_ID=<old_map_id>;

-- 7b-iv: Patch TASK.MAP_ID — one statement per EXPRESSION-bearing task
UPDATE TASK SET MAP_ID=<new_map_id>
WHERE WF_TEMPLATE_ID=<target_wft_id> AND VERSION=<new_version> AND TASK_ID=<task_id>;

-- STEP 8: WF_STEP_REF
INSERT INTO WF_STEP_REF (WF_TEMPLATE_ID, WF_TEMPLATE_VERSION, STEP_ID, USAGE_TYPE, REF_TYPE,
  TASK_REF_TYPE, CONTEXT_TYPE, RECORD_ID, REF_TASK_ID, MODULE_ID, OBJECT_TYPE_ID, SECTION, FIELD, ASSOC)
SELECT <target_wft_id>, <new_version>, STEP_ID, USAGE_TYPE, REF_TYPE, TASK_REF_TYPE, CONTEXT_TYPE,
  RECORD_ID, REF_TASK_ID, MODULE_ID, OBJECT_TYPE_ID, SECTION, FIELD, ASSOC
FROM WF_STEP_REF WHERE WF_TEMPLATE_ID=<src_wft_id> AND WF_TEMPLATE_VERSION=<current_max>;

-- STEP 9: TASK_REMINDER
INSERT INTO TASK_REMINDER (REMINDER_ID, TASK_ID, SUBSCRIBER_ID, DAYS, HOURS, MINUTES,
  REMINDER_DATETIME, CREATED_BY, TASK_DUE_DATETIME, REMINDER_TYPE, ACTIVE_FLAG, NOTE_ID,
  WF_TEMPLATE_ID, WF_TEMPLATE_VERSION)
SELECT REMINDER_ID, TASK_ID, SUBSCRIBER_ID, DAYS, HOURS, MINUTES, REMINDER_DATETIME,
  CREATED_BY, TASK_DUE_DATETIME, REMINDER_TYPE, ACTIVE_FLAG, NOTE_ID, <target_wft_id>, <new_version>
FROM TASK_REMINDER WHERE WF_TEMPLATE_ID=<src_wft_id> AND WF_TEMPLATE_VERSION=<current_max>;

-- STEP 10: TASK_FILTER
INSERT INTO TASK_FILTER (TASK_ID, WF_TEMPLATE_ID, WF_TEMPLATE_VERSION, SEQUENCE_ID,
  LEFT_SECTION_NAME, LEFT_FIELD_NAME, LEFT_DATA_TYPE, LEFT_LIST_FLAG, OPERATOR, RIGHT_LIST_TYPE,
  RIGHT_SECTION_NAME, RIGHT_FIELD_NAME, RIGHT_DATA_TYPE, CONSTANT_FLAG, RIGHT_VALUE,
  RIGHT_LIST_FLAG, ASSOCIATED_TO_TASK, ASSOCIATED_TO_CHILDREN, ASSOCIATED_TO_NAME)
SELECT TASK_ID, <target_wft_id>, <new_version>, SEQUENCE_ID, LEFT_SECTION_NAME, LEFT_FIELD_NAME,
  LEFT_DATA_TYPE, LEFT_LIST_FLAG, OPERATOR, RIGHT_LIST_TYPE, RIGHT_SECTION_NAME, RIGHT_FIELD_NAME,
  RIGHT_DATA_TYPE, CONSTANT_FLAG, RIGHT_VALUE, RIGHT_LIST_FLAG, ASSOCIATED_TO_TASK,
  ASSOCIATED_TO_CHILDREN, ASSOCIATED_TO_NAME
FROM TASK_FILTER WHERE WF_TEMPLATE_ID=<src_wft_id> AND WF_TEMPLATE_VERSION=<current_max>;

-- STEP 11: NOTE
INSERT INTO NOTE (NOTE_ID, POSITION, TEXT, PROJECT_ID, OBJECT_ID, OBJECT_TYPE_ID, NOTETYPE,
  REVISION, AUTHOR_ID, CREATE_DATE, UPDATED_BY, UPDATED_DATE, WF_TEMPLATE_ID, WF_TEMPLATE_VERSION)
SELECT NOTE_ID, POSITION, TEXT, PROJECT_ID, OBJECT_ID, OBJECT_TYPE_ID, NOTETYPE,
  REVISION, AUTHOR_ID, CREATE_DATE, UPDATED_BY, UPDATED_DATE, <target_wft_id>, <new_version>
FROM NOTE WHERE WF_TEMPLATE_ID=<src_wft_id> AND WF_TEMPLATE_VERSION=<current_max>;

-- STEP 12: ATTACHMENT
INSERT INTO ATTACHMENT (ATTACHMENT_ID, ATTACH_SIZE, NAME, LOCATION, PROJECT_ID, OBJECT_ID,
  OBJECT_TYPE_ID, ATTACHMENT_TYPE, REVISION, AUTHOR_ID, CREATE_DATE, UPDATED_BY, UPDATED_DATE,
  WF_TEMPLATE_ID, WF_TEMPLATE_VERSION)
SELECT ATTACHMENT_ID, ATTACH_SIZE, NAME, LOCATION, PROJECT_ID, OBJECT_ID, OBJECT_TYPE_ID,
  ATTACHMENT_TYPE, REVISION, AUTHOR_ID, CREATE_DATE, UPDATED_BY, UPDATED_DATE,
  <target_wft_id>, <new_version>
FROM ATTACHMENT WHERE WF_TEMPLATE_ID=<src_wft_id> AND WF_TEMPLATE_VERSION=<current_max>;

-- STEP 13: WF_PARAMS_MAP
INSERT INTO WF_PARAMS_MAP (PARAMS_MAP_ID, WF_TEMPLATE_ID, WF_TEMPLATE_VERSION, PARAM_ID,
  TYPE, VAR_ID, VAR_MODULE_ID, VAR_BO_ID, NAME)
SELECT PARAMS_MAP_ID, <target_wft_id>, <new_version>, PARAM_ID, TYPE, VAR_ID, VAR_MODULE_ID, VAR_BO_ID, NAME
FROM WF_PARAMS_MAP WHERE WF_TEMPLATE_ID=<src_wft_id> AND WF_TEMPLATE_VERSION=<current_max>;
```

### Apply User Modifications
Execute ADD / REMOVE / UPDATE changes after Steps 1–13. Re-run Section 14 + V1–V11.

### Publication (user confirms after all validations pass)
```sql
UPDATE WF_TEMPLATE SET STATUS_ID=10 WHERE WF_TEMPLATE_ID=<target_wft_id> AND WF_TEMPLATE_VERSION=<new_version>;
UPDATE WF_TEMPLATE SET STATUS_ID=25 WHERE WF_TEMPLATE_ID=<src_wft_id> AND WF_TEMPLATE_VERSION=<current_max>;
-- Confirm:
SELECT WF_TEMPLATE_ID, WF_TEMPLATE_VERSION, STATUS_ID FROM WF_TEMPLATE WHERE WF_TEMPLATE_ID=<target_wft_id> ORDER BY WF_TEMPLATE_VERSION DESC;
```

---

## SECTION 16 — AGENT BEHAVIOR

- R16.1: Present summary of all planned changes. Wait for explicit user confirmation before any INSERT, UPDATE, or DELETE.
- R16.2: All SQL statements issued via the MCP SQL tool must contain fully resolved literal values. Never use `?` placeholder syntax or `:param` named parameter notation in any generated SQL — these are JDBC conventions and have no meaning in direct SQL execution.
- R16.3: Log every SQL statement executed and its row count. Surface full log to user at session end.
- R16.4: On any MCP tool error or unexpected schema result, stop and report. Never silently infer or work around.
- R16.5: Use queryTririgaDatabase for all DB reads. Use getTririgaWFTaskTypeToNameMapping for task type resolution — never replicate as raw SQL.
- R16.6: All sequence NEXTVAL calls are issued as individual SELECT statements via the MCP SQL tool. The agent reads and stores each returned value immediately. That stored literal is substituted into all subsequent SQL statements that require it. Each sequence is called exactly once per logical record (Rule R4.3).
- R16.7: All Section 13 validations + Section 14 branch tracing must pass before publication. Any single failure is blocking. Report: which check failed, which step/map/token caused it, corrective action needed.
- R16.8: Never fabricate values for any column. Query an existing same-type record as reference when uncertain.
- R16.9: When creating any new step, collect TASK_LABEL from user and optionally DESCRIPTION.
- R16.10: All clone-phase operations use the SELECT/INSERT pattern as defined in Section 15. Never specify individual column values for cloned rows — always SELECT from source.

---

## OPEN ITEMS

| # | Rule | Topic |
|---|---|---|
| 2 | R10.10 | OPERATOR values 20, 21, 30–35 — not confirmed |
| 3 | R8.7 | FORMULA functions beyond startsWith — not confirmed |
| 4 | R8.9 | PARAM_STR item attribute values beyond 'Result Count' — not confirmed |
| 7 | S1.2 | TASK_TYPEs 3,5,6,7,8,15,16 (trailing - names) — bare assumed, not confirmed |
| 8 | R6 | MAP_TYPE 30 — meaning not fully confirmed |
| 11 | R1.13 | TASK_TYPE 29 FILTER_OBJECT — meaning not determined |
