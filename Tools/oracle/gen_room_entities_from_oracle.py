#!/usr/bin/env python3
"""Generate Room @Entity classes for the tables the Android tree is missing.

WHY A GENERATOR

`schema_oracle.json` is the shared Room<->GRDB contract: it states every column's name, SQLite affinity,
NOT NULL, DEFAULT, the primary-key composition and the explicit indices, for all 72 tables. The Android
tree had 37 of them. Writing 36 entities by hand would be 36 chances to disagree with that file on
column ORDER, which `SchemaOracleTest` compares positionally — a class where two columns are swapped is
otherwise indistinguishable from a working one until the suite runs.

So the entities are derived from the same file the test reads. Change the oracle, re-run this, and the
entities follow.

AFFINITY MAPPING

    TEXT -> String      INTEGER -> Long      REAL -> Double
    NUMERIC -> Boolean  BLOB   -> ByteArray

NUMERIC is mapped to Boolean because GRDB writes a Swift `Bool` with NUMERIC affinity. That set is
read from the schema's own DDL (`Database.swift`, every `.boolean` column) rather than guessed from column
names, and `verify_boolean_numerics` fails the run if the oracle contains a NUMERIC column the DDL does not
declare `.boolean` — so the mapping is checked against its source on every run instead of trusted.

Usage:
    Tools/oracle/gen_room_entities_from_oracle.py [--check]

`--check` verifies the committed output matches what would be generated, without writing.
"""

from __future__ import annotations

import argparse
import json
import keyword
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ORACLE = ROOT / "Packages" / "WhoopStore" / "Tests" / "WhoopStoreTests" / "Resources" / "schema_oracle.json"
ANDROID_ORACLE = ROOT / "android" / "app" / "src" / "test" / "resources" / "schema_oracle.json"
OUT = ROOT / "android" / "app" / "src" / "main" / "java" / "com" / "noop" / "data" / "EntitiesForkExtras.kt"
MIGRATION_OUT = ROOT / "android" / "app" / "src" / "main" / "java" / "com" / "noop" / "data" / "MigrationForkExtras.kt"

# The Android oracle as upstream last had it: the reference for "which tables Android already has".
UPSTREAM_REF = "upstream/main"

# Room schema version the Android tree is on, and the one this migration targets.
FROM_VERSION = 41
TO_VERSION = 42

AFFINITY = {
    "TEXT": "String",
    "INTEGER": "Long",
    "REAL": "Double",
    "NUMERIC": "Boolean",
    "BLOB": "ByteArray",
}

DATABASE_DDL = ROOT / "Packages" / "WhoopStore" / "Sources" / "WhoopStore" / "Database.swift"
# GRDB spells a column two ways in this schema and both appear: `t.column("x", .boolean)` for columns
# created with the table, and `t.add(column: "x", .boolean)` for columns added to an existing table by a
# later migration. Matching only the first silently reported three genuine booleans (`battery.charging`,
# `dailyMetric.sleepHrOnly`, `sleepSession.userEdited`) as unclassified.
BOOLEAN_COLUMN = re.compile(
    r't\.(?:column\(|add\(column:\s*)"([A-Za-z0-9_]+)"\s*,\s*\.boolean\b'
)


def boolean_columns() -> set[str]:
    """Every column the GRDB schema declares `.boolean`.

    GRDB writes a Swift `Bool` with NUMERIC affinity, which is why the oracle records NUMERIC for these
    rather than INTEGER. Deriving the set from the DDL instead of from column NAMES means the
    NUMERIC->Boolean mapping rests on the schema's own declaration. A name heuristic was tried first and
    rejected: it flagged `energyCalibrationModel.enabled`, which the DDL shows is a genuine `.boolean`, so
    the heuristic would have pushed a human toward widening a pattern instead of checking the source.
    """
    return set(BOOLEAN_COLUMN.findall(DATABASE_DDL.read_text()))


def pascal(name: str) -> str:
    return "".join(part[:1].upper() + part[1:] for part in re.split(r"[^A-Za-z0-9]", name) if part)


def kotlin_prop(name: str) -> str:
    """A safe Kotlin property name for a column, preserving the oracle's spelling where possible."""
    if name in keyword.kwlist or not re.match(r"^[A-Za-z_][A-Za-z0-9_]*$", name):
        return "`" + name.replace("`", "") + "`"
    return name


def verify_boolean_numerics(tables: dict) -> None:
    declared = boolean_columns()
    bad = [
        f"{tbl}.{col['name']}"
        for tbl, spec in sorted(tables.items())
        for col in spec["columns"]
        if col["affinity"] == "NUMERIC" and col["name"] not in declared
    ]
    if bad:
        sys.exit(
            "ERROR: NUMERIC columns the GRDB DDL does not declare `.boolean`. The NUMERIC->Boolean "
            "mapping rests on that declaration, so these need a decision, not a default:\n  "
            + "\n  ".join(bad)
        )


def render(specs: dict) -> str:
    out: list[str] = []
    out.append("package com.noop.data")
    out.append("")
    out.append("import androidx.room.ColumnInfo")
    out.append("import androidx.room.Entity")
    out.append("import androidx.room.Index")
    out.append("import androidx.room.PrimaryKey")
    out.append("")
    out.append("/*")
    out.append(" * EntitiesForkExtras.kt - the Room half of the tables the Android tree was missing.")
    out.append(" *")
    out.append(" * GENERATED by Tools/oracle/gen_room_entities_from_oracle.py from schema_oracle.json. Do not hand-edit:")
    out.append(" * `SchemaOracleTest` compares column names IN ORDER, affinity, NOT NULL, DEFAULT, the primary")
    out.append(" * key and the indices against that file, so a reordered column fails the suite.")
    out.append(" *")
    out.append(" * Each table below exists on the GRDB side and had no Room twin, because the working fork carried")
    out.append(" * no Android app. The bodies are empty on purpose: the schema lands first, so a migration can be")
    out.append(" * written and tested against real columns before any screen reads them.")
    out.append(" */")
    out.append("")

    for table in sorted(specs):
        spec = specs[table]
        cls = pascal(table)
        pk = list(spec.get("primaryKey") or [])
        indices = spec.get("indices") or []

        # @Entity on ONE line, always. A wrapped form (`@Entity(tableName = "x"` / newline / `primaryKeys
        # = [...]` / newline / `)`) reads fine to a human and is what this generator emitted first, but
        # kotlinc rejected it with "Expecting an element" pointing at the second line, and Room's KSP
        # processor then failed with `KSErrorType cannot be cast to String`. Single-line is not a
        # style preference here; it is what the parser accepts.
        args = [f'tableName = "{table}"']
        if len(pk) > 1:
            args.append("primaryKeys = [" + ", ".join(f'"{c}"' for c in pk) + "]")
        if indices:
            parts = []
            for i in indices:
                cols = ", ".join(f'"{c}"' for c in i["columns"])
                unique = ", unique = true" if i.get("unique") else ""
                parts.append(f'Index(value = [{cols}], name = "{i["name"]}"{unique})')
            args.append("indices = [" + ", ".join(parts) + "]")
        out.append("@Entity(" + ", ".join(args) + ")")

        out.append(f"data class {cls}(")
        fields = []
        for col in spec["columns"]:
            is_pk = col["name"] in pk
            ktype = AFFINITY[col["affinity"]]
            # A Room primary key must be non-null regardless of what GRDB recorded, so a PK column is
            # forced non-null here. Where GRDB left it nullable, the oracle carries an `android.notNull`
            # override so the comparison stays honest instead of silently passing.
            if not col["notNull"] and not is_pk:
                ktype += "?"
            anns = []
            if is_pk and len(pk) == 1:
                anns.append("@PrimaryKey")
            colinfo = [f'name = "{col["name"]}"']
            if col.get("default") is not None:
                colinfo.append(f'defaultValue = "{col["default"]}"')
            anns.append("@ColumnInfo(" + ", ".join(colinfo) + ")")
            fields.append(f"    {' '.join(anns)} val {kotlin_prop(col['name'])}: {ktype}")
        out.append(",\n".join(fields))
        out.append(")")
        out.append("")

    return "\n".join(out).rstrip() + "\n"


# The DDL must declare the type ROOM will check, which is the Kotlin type in the @Entity — not GRDB's
# affinity. They differ for booleans: GRDB writes a Swift Bool as NUMERIC, the entity declares Kotlin
# Boolean, and Room's own TableInfo expects INTEGER. Creating the column as NUMERIC leaves the migrated
# table's declared type disagreeing with the entity, and WhoopDatabaseUpgradeTest then rejects the
# migration ("Migration didn't properly handle") even though the values are identical. TEXT/INTEGER/REAL/
# BLOB map through unchanged.
SQL_TYPE = {"TEXT": "TEXT", "INTEGER": "INTEGER", "REAL": "REAL", "NUMERIC": "INTEGER", "BLOB": "BLOB"}


def render_migration(specs: dict, from_version: int, to_version: int) -> str:
    """Room Migration body: the DDL for every table Android is gaining.

    Generated from the same oracle as the entities, so the two cannot disagree: a column that exists in
    the @Entity but not in the CREATE TABLE (or in the other order) is exactly the class of bug a
    hand-written migration for 36 tables would produce, and it would only surface as a runtime crash on a
    user's existing database rather than as a test failure.
    """
    out = [
        f"    // GENERATED by Tools/oracle/gen_room_entities_from_oracle.py from schema_oracle.json.",
        f"    // Migration {from_version} -> {to_version}: {len(specs)} tables that exist on GRDB and had no Room",
        f"    // twin, because the working fork carried no Android app. Empty on upgrade - these are all new",
        f"    // tables, so no existing row is touched and there is nothing to backfill yet.",
        f"    val FORK_{from_version}_{to_version} = object : Migration({from_version}, {to_version}) {{",
        f"        override fun migrate(db: SupportSQLiteDatabase) {{",
    ]
    body: list[str] = []
    for table in sorted(specs):
        spec = specs[table]
        pk = list(spec.get("primaryKey") or [])
        cols = []
        for col in spec["columns"]:
            piece = f"{col['name']} {SQL_TYPE[col['affinity']]}"
            if col["notNull"] or col["name"] in pk:
                piece += " NOT NULL"
            if col.get("default") is not None:
                piece += f" DEFAULT {col['default']}"
            cols.append(piece)
        stmt = (
            f"CREATE TABLE IF NOT EXISTS {table} ("
            + ", ".join(cols)
            + ", PRIMARY KEY(" + ", ".join(pk) + "))"
        )
        body.append(f'            db.execSQL("{stmt}")')
        for i in spec.get("indices") or []:
            uniq = "UNIQUE " if i.get("unique") else ""
            icols = ", ".join(i["columns"])
            body.append(
                f'            db.execSQL("CREATE {uniq}INDEX IF NOT EXISTS {i["name"]} '
                f'ON {table} ({icols})")'
            )
    out.extend(body)
    out.extend(EXTRA_ALTER)
    out.append("        }")
    out.append("    }")
    return "\n".join(out)


# Columns the Forge fork ADDED to a table Room already had, so they are an ALTER rather than part of the
# 36 new tables. Kept explicit rather than derived: the generator's job is the new tables, and an ALTER on
# a shared table is a different kind of change that deserves to be read on its own line.
EXTRA_ALTER = [
    "            // The Forge fork added `transport` to rrInterval on GRDB (recipe AI-12: choose a WHOOP's",
    "            // R-R delivery path per five-minute segment). Additive, nullable, no backfill - rows written",
    "            // before the path was recorded stay null, which is the unknown-delivery case.",
    "            db.execSQL(\"ALTER TABLE rrInterval ADD COLUMN transport INTEGER\")",
]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Verify committed output; write nothing.")
    args = parser.parse_args()

    oracle = json.loads(ORACLE.read_text())
    tables = oracle["tables"]
    verify_boolean_numerics(tables)

    # WHICH tables to generate: those in the GRDB oracle that the Android oracle does not have. Comparing
    # against the Android oracle rather than against the entity sources is deliberate — `cursors`,
    # `ouraRaw` and `rawBatch` are listed in BOTH oracles and are `ios_only` here, yet upstream ships no
    # Room entity for any of them and its suite is green. They are a tolerated divergence, so generating
    # them would fix something nobody asked for and risk that tolerance. The set is therefore computed
    # from the two oracles, which is also exactly the 36-table gap the migration has to cover.
    prior = json.loads(
        subprocess.run(
            ["git", "show", f"{UPSTREAM_REF}:{ANDROID_ORACLE.relative_to(ROOT)}"],
            cwd=ROOT, capture_output=True, text=True, check=True,
        ).stdout
    )
    had = set(prior["tables"])
    wanted = {name: spec for name, spec in tables.items() if name not in had}

    generated = render(wanted)

    if args.check:
        current = OUT.read_text() if OUT.exists() else ""
        if current != generated:
            print(f"FAIL {OUT.relative_to(ROOT)} is stale; re-run the generator")
            return 1
        print(f"OK {OUT.relative_to(ROOT)} matches the oracle ({len(wanted)} tables)")
        return 0

    OUT.write_text(generated)
    MIGRATION_OUT.write_text(
        "package com.noop.data\n\n"
        "import androidx.room.migration.Migration\n"
        "import androidx.sqlite.db.SupportSQLiteDatabase\n\n"
        "object MigrationForkExtras {\n"
        + render_migration(wanted, FROM_VERSION, TO_VERSION) + "\n}\n"
    )
    # `SchemaOracleTest.loadRoomSchema` asserts the oracle's roomVersion EQUALS Room's @Database version,
    # so the oracle moves with the migration.
    oracle["roomVersion"] = TO_VERSION

    # The 36 tables just gained a Room twin, so they are no longer ios_only. `SchemaOracleTest` says so
    # directly: a table declared ios_only that Room now declares "gained a twin: move it to platform
    # 'both'". The ios_only `reason` text described the absence of a Room twin, which is now false, so it
    # is replaced rather than left to rot into a lie.
    for name in wanted:
        spec = oracle["tables"][name]
        spec.pop("reason", None)
        spec["platform"] = "both"
        pk = set(spec.get("primaryKey") or [])
        for col in spec["columns"]:
            # Two places Room cannot match GRDB, and the fixture's own per-column `android` override is
            # where that belongs. Neither is drift; both are stated consequences of the platform.
            override = {}
            if col["name"] in pk and not col["notNull"]:
                # Room makes a primary key NOT NULL whether or not GRDB did, and Room has no way to say
                # otherwise. GRDB's nullable PK is the looser statement; Room's is what it will create.
                override["notNull"] = True
            if col["affinity"] == "NUMERIC":
                # GRDB writes a Swift Bool as NUMERIC; Room writes a Kotlin Boolean as INTEGER. Same value,
                # different declared type, so the Android side is recorded as INTEGER.
                override["affinity"] = "INTEGER"
            if override:
                col["android"] = override

    # Android-only tables have to survive: `coachMessage` exists in Room and has no GRDB twin, and the
    # test fails on any Room table the oracle does not mention. They are carried over verbatim from the
    # Android oracle this tree started from, so nothing about them is invented here.
    for name, spec in prior["tables"].items():
        if name not in oracle["tables"]:
            # It exists in Room and has no GRDB entry, so it is android_only by construction. The prior
            # copy may label it differently; `platform` is the flag that says which side owns it, and it
            # has to be right or the table reads as a shared contract that only one half honours.
            spec = dict(spec)
            spec["platform"] = "android_only"
            oracle["tables"][name] = spec

    ORACLE.write_text(json.dumps(oracle, indent=2, ensure_ascii=False) + "\n")
    # Keep both oracle copies in lockstep, as SchemaOracleTest requires byte-identity.
    ANDROID_ORACLE.write_bytes(ORACLE.read_bytes())
    print(f"wrote {OUT.relative_to(ROOT)} ({len(wanted)} tables)")
    print(f"wrote {MIGRATION_OUT.relative_to(ROOT)}")
    print(f"synced {ANDROID_ORACLE.relative_to(ROOT)}")
    print(f"bump Room version {FROM_VERSION} -> {TO_VERSION} and register the migration")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())