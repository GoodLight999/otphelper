#!/usr/bin/env python3
"""Verify that Android backup rules preserve settings but exclude OTP history."""

from __future__ import annotations

import sys
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
DATA_EXTRACTION_RULES = ROOT / "app/src/main/res/xml/data_extraction_rules.xml"
LEGACY_BACKUP_RULES = ROOT / "app/src/main/res/xml/backup_rules.xml"
EXPECTED_INCLUDE = {"domain": "file", "path": "datastore/."}


def fail(message: str) -> "NoReturn":
    raise SystemExit(message)


def assert_single_datastore_include(parent: ET.Element, label: str) -> None:
    children = list(parent)
    if len(children) != 1:
        fail(f"{label} must contain exactly one rule; found {len(children)}")

    include = children[0]
    if include.tag != "include":
        fail(f"{label} must contain an include rule, found <{include.tag}>")
    if include.attrib != EXPECTED_INCLUDE:
        fail(
            f"{label} include must be exactly {EXPECTED_INCLUDE}, "
            f"found {include.attrib}"
        )


def verify_android_12_plus() -> None:
    root = ET.parse(DATA_EXTRACTION_RULES).getroot()
    if root.tag != "data-extraction-rules":
        fail("Android 12+ backup file has the wrong root element")

    children = list(root)
    tags = [child.tag for child in children]
    if tags != ["cloud-backup", "device-transfer"]:
        fail(
            "Android 12+ backup rules must contain cloud-backup then "
            f"device-transfer exactly; found {tags}"
        )

    cloud, transfer = children
    if cloud.attrib != {"disableIfNoEncryptionCapabilities": "true"}:
        fail(
            "cloud-backup must require encryption capabilities; "
            f"found attributes {cloud.attrib}"
        )
    if transfer.attrib:
        fail(f"device-transfer must not carry unexpected attributes: {transfer.attrib}")

    assert_single_datastore_include(cloud, "cloud-backup")
    assert_single_datastore_include(transfer, "device-transfer")


def verify_android_11_and_lower() -> None:
    root = ET.parse(LEGACY_BACKUP_RULES).getroot()
    if root.tag != "full-backup-content":
        fail("Legacy backup file has the wrong root element")
    if root.attrib:
        fail(f"Legacy backup root has unexpected attributes: {root.attrib}")
    assert_single_datastore_include(root, "legacy full-backup-content")


def verify_no_sample_placeholders() -> None:
    for path in (DATA_EXTRACTION_RULES, LEGACY_BACKUP_RULES):
        text = path.read_text(encoding="utf-8")
        lowered = text.lower()
        if "todo" in lowered or "sample data extraction rules" in lowered:
            fail(f"Sample/TODO backup rules remain in {path.relative_to(ROOT)}")


def main() -> int:
    verify_android_12_plus()
    verify_android_11_and_lower()
    verify_no_sample_placeholders()
    print("Backup privacy rules verified: DataStore settings only; OTP history excluded.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
