"""Missing private configuration must never reset an existing database or API budget."""
import os
from pathlib import Path
import subprocess
import sys

import pytest


SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "init_runtime.py"


def initialize(path):
    env = os.environ.copy()
    env["PRIVATE_RUNTIME_ENV"] = str(path)
    return subprocess.run([sys.executable, str(SCRIPT)], env=env,
                          capture_output=True, text=True, timeout=10)


@pytest.mark.parametrize("existing", ["postgres/PG_VERSION", "data/journal.sqlite3", "postgres-volume.json"])
def test_missing_config_with_existing_state_requires_recovery(tmp_path, existing):
    state = tmp_path / existing
    state.parent.mkdir(parents=True,exist_ok=True)
    state.write_bytes(b"existing-private-state")
    config = tmp_path / "private-env.ps1"

    result = initialize(config)

    assert result.returncode != 0
    assert not config.exists()
    assert state.read_bytes() == b"existing-private-state"
    assert "restore" in result.stderr.lower()


def test_existing_configuration_is_preserved(tmp_path):
    config = tmp_path / "private-env.ps1"
    config.write_bytes(b"existing-configuration")

    result = initialize(config)

    assert config.read_bytes() == b"existing-configuration"
    assert "preserved" in result.stdout.lower() + result.stderr.lower()


def test_fresh_configuration_is_private_and_quotes_paths(tmp_path):
    config = tmp_path / "owner's runtime" / "private-env.ps1"

    result = initialize(config)

    assert result.returncode == 0
    text = config.read_text(encoding="utf-8")
    assert "owner''s runtime" in text
    secret = next(line.split("='", 1)[1][:-1] for line in text.splitlines()
                  if line.startswith("$env:PRIVATE_ASSISTANT_SECRET="))
    assert len(secret) >= 64
    assert secret not in result.stdout + result.stderr
    assert '$env:PRIVATE_PG_VOLUME=' in text
    assert '$env:PRIVATE_PG_DATA=' not in text


def test_separate_runtime_paths_get_distinct_native_volume_names(tmp_path):
    first=tmp_path/'one'/'private-env.ps1';second=tmp_path/'two'/'private-env.ps1'
    assert initialize(first).returncode==0 and initialize(second).returncode==0
    volume=lambda p: next(row for row in p.read_text().splitlines() if 'PRIVATE_PG_VOLUME' in row)
    assert volume(first)!=volume(second)
