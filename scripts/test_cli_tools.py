"""CLI tools must preserve structured output and remain inert when imported."""

import contextlib
import importlib.util
import io
import json
from pathlib import Path
import runpy
import sys
import unittest
from unittest import mock

SCRIPTS = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPTS))
from cli_output import write_json, write_text


class CliToolsTest(unittest.TestCase):
    def test_json_output_remains_machine_readable(self):
        output = io.StringIO()
        payload = {"status": "通过", "tables": 115}
        with contextlib.redirect_stdout(output):
            write_json(payload, ensure_ascii=False, indent=2)
        self.assertEqual(payload, json.loads(output.getvalue()))
        self.assertEqual(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", output.getvalue())

    def test_path_output_preserves_the_cli_protocol(self):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            write_text(Path("db/schema.sql"))
        self.assertEqual(str(Path("db/schema.sql")) + "\n", output.getvalue())

    def test_importing_database_tools_cannot_run_mutations(self):
        for name in ("prepare_lamp_rehearsal.py", "apply_lamp_preparation.py"):
            with self.subTest(tool=name), mock.patch("subprocess.run") as execute:
                runpy.run_path(str(SCRIPTS / name), run_name="tool_under_test")
                execute.assert_not_called()

    def test_wrong_database_is_rejected_before_process_execution(self):
        tool = runpy.run_path(str(SCRIPTS / "prepare_lamp_rehearsal.py"), run_name="tool_under_test")
        with mock.patch("subprocess.run") as execute:
            with self.assertRaisesRegex(RuntimeError, "Only the rehearsal database"):
                tool["execute"]("SELECT 1", "other_database")
            execute.assert_not_called()


if __name__ == "__main__":
    unittest.main()
