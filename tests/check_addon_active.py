"""CI helper: assert every add-on in a profile is installed and active.

Usage: python tests/check_addon_active.py <profile_dir>
"""
import json
import os
import sys

prof = sys.argv[1]
wanted = {f[:-4] for f in os.listdir(os.path.join(prof, "extensions")) if f.endswith(".xpi")}
with open(os.path.join(prof, "extensions.json"), encoding="utf-8") as f:
    addons = {a["id"]: a for a in json.load(f)["addons"]}
for ext_id in sorted(wanted):
    a = addons.get(ext_id)
    state = "missing" if a is None else f"active={a.get('active')} userDisabled={a.get('userDisabled')}"
    print(f"{ext_id}: {state}")
    if a is None or not a.get("active"):
        sys.exit(f"add-on {ext_id} is not active")
print("all add-ons active")
