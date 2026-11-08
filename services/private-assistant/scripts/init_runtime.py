"""Create private configuration once. Values are never printed or stored in the repository."""
import os
import secrets
from pathlib import Path

path = Path(os.environ.get("PRIVATE_RUNTIME_ENV", "D:/AI-MES-Stage2/private-env.ps1"))
if path.exists():
    raise SystemExit("Existing configuration preserved")
root = path.parent/"data"
for state in (root, path.parent/"postgres"):
    if state.exists() and (not state.is_dir() or next(state.iterdir(), None) is not None):
        raise SystemExit("Existing private state detected: restore its original configuration; "
                         "refusing to generate replacement credentials or reset the request budget")
root.mkdir(parents=True, exist_ok=True)
values = {"PRIVATE_ASSISTANT_ROOT": str(root), "PRIVATE_ASSISTANT_SECRET": secrets.token_hex(32),
          "PRIVATE_PG_PASSWORD": secrets.token_hex(24), "PRIVATE_PG_DATA": str(path.parent/"postgres").replace('\\','/'),
          "PRIVATE_LLM_LIMIT": "0"}
def quoted(value):
    return value.replace("'", "''")


with path.open("x", encoding="utf-8") as config:
    config.write(''.join(f"$env:{key}='{quoted(value)}'\n" for key,value in values.items()))
print("Created private environment configuration; values hidden")
