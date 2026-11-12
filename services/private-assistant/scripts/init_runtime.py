"""Create private configuration once. Values are never printed or stored in the repository."""
import os
import secrets
import hashlib
import json
from pathlib import Path

path = Path(os.environ.get("PRIVATE_RUNTIME_ENV", "D:/AI-MES-Stage2/private-env.ps1"))
if path.exists():
    raise SystemExit("Existing configuration preserved")
if (path.parent/'postgres-volume.json').exists():
    raise SystemExit('Existing native database volume detected: restore its original configuration')
root = path.parent/"data"
for state in (root, path.parent/"postgres"):
    if state.exists() and (not state.is_dir() or next(state.iterdir(), None) is not None):
        raise SystemExit("Existing private state detected: restore its original configuration; "
                         "refusing to generate replacement credentials or reset the request budget")
root.mkdir(parents=True, exist_ok=True)
values = {"PRIVATE_ASSISTANT_ROOT": str(root), "PRIVATE_ASSISTANT_SECRET": secrets.token_hex(32),
          "PRIVATE_PG_PASSWORD": secrets.token_hex(24),
          "PRIVATE_PG_VOLUME": 'mes-private-pg-'+hashlib.sha256(str(path.parent.resolve()).casefold().encode()).hexdigest()[:16],
          "PRIVATE_LLM_LIMIT": "0"}
def quoted(value):
    return value.replace("'", "''")


with path.open("x", encoding="utf-8") as config:
    config.write(''.join(f"$env:{key}='{quoted(value)}'\n" for key,value in values.items()))
with (path.parent/'postgres-volume.json').open('x',encoding='utf-8') as marker:
    json.dump({'volume':values['PRIVATE_PG_VOLUME'],'purpose':'MES private PostgreSQL data; keep with original credentials'},marker)
print("Created private environment configuration; values hidden")
