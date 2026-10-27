import tempfile
import unittest
from pathlib import Path

from tools.oai_ue_diag_collect import (
    CommandResult,
    docker_log_filename,
    format_command_error,
    sanitize_filename,
    scan_log_patterns,
)


class CollectorHelperTest(unittest.TestCase):
    def test_sanitize_filename(self):
        self.assertEqual("oai-gnb", sanitize_filename("oai/gnb"))
        self.assertEqual("oai_amf.1", sanitize_filename(" oai_amf.1 "))
        self.assertEqual("unknown", sanitize_filename("///"))

    def test_docker_log_filename(self):
        self.assertEqual("gnb.log", docker_log_filename("oai-gnb"))
        self.assertEqual("docker-custom-gnb.log", docker_log_filename("custom/gnb"))

    def test_format_command_error(self):
        result = CommandResult(command=["adb"], returncode=1, stderr="no devices")
        self.assertIn("exit 1", format_command_error("adb devices", result))
        skipped = CommandResult(command=["adb"], returncode=None, stderr="missing executable: adb", skipped=True)
        self.assertIn("skipped", format_command_error("adb devices", skipped))

    def test_scan_log_patterns_limits_matches(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "amf.log"
            path.write_text(
                "\n".join(
                    [
                        "InitialUEMessage received",
                        "Registration Request",
                        "Registration Reject cause 15",
                    ]
                ),
                encoding="utf-8",
            )

            findings = scan_log_patterns([path], patterns=["Registration", "Cause"], per_pattern_limit=1)

        self.assertEqual(1, len(findings["Registration"]))
        self.assertEqual("amf.log", findings["Registration"][0]["file"])
        self.assertEqual(3, findings["Cause"][0]["line"])


if __name__ == "__main__":
    unittest.main()
