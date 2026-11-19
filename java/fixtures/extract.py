#!/usr/bin/env python3
"""Project the unchanged upstream JSON vector into dependency-free Java test input."""
import base64
import json
from pathlib import Path
import sys

root = Path(__file__).resolve().parent
values = {}


def flatten(obj, prefix=""):
    for key, value in obj.items():
        name = (prefix + "." + key).strip(".")
        if isinstance(value, dict):
            flatten(value, name)
        elif isinstance(value, str):
            values[name] = base64.urlsafe_b64encode(value.encode()).decode().rstrip("=")
        else:
            values[name] = str(value)


flatten(json.loads((root / "upstream-e2ee-v2.json").read_text()))
output = "# Text fields base64url-encoded by fixture extraction; numeric fields decimal.\n"
output += "\n".join(key + "=" + value for key, value in values.items()) + "\n"
target = root / "vector.properties"
if "--check" in sys.argv:
    if target.read_text() != output:
        raise SystemExit("Fixture projection mismatch; run fixtures/extract.py")
    print("PASS fixture projection matches upstream JSON")
else:
    target.write_text(output)
