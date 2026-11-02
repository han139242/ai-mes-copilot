"""Machine-readable command output; keep separate from diagnostic logging."""

import json
import sys


def write_json(value, **options):
    """Emit one JSON document with the same formatting options as json.dumps."""
    json.dump(value, sys.stdout, **options)
    sys.stdout.write("\n")


def write_text(value):
    """Emit a command result such as a path or a database result table."""
    sys.stdout.write(str(value) + "\n")
