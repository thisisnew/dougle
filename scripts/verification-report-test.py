import importlib.util
import os
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("verification_report", Path(__file__).with_name("verification-report.py"))
report = importlib.util.module_from_spec(spec)
spec.loader.exec_module(report)


class VerificationReportTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / "TEST-synthetic.xml"

    def write(self, body, count):
        self.path.write_text('<testsuite tests="' + str(count) + '">' + body + '</testsuite>')

    def test_distinguishes_pass_failure_and_skip(self):
        self.write('<testcase classname="synthetic.Matrix" name="case1"/>'
                   '<testcase classname="synthetic.Matrix" name="case2"><failure/></testcase>'
                   '<testcase classname="synthetic.Other" name="case3"><skipped/></testcase>', 3)
        result = report.collect(self.directory.name, 0, ["Matrix"])
        self.assertEqual(result["summary"], {"PASS": 1, "FAIL": 1, "SKIPPED": 1})
        self.assertEqual(result["matrixSummary"], {"PASS": 1, "FAIL": 1, "SKIPPED": 0})

    def test_rejects_stale_reports(self):
        self.write('<testcase classname="synthetic.Matrix" name="case1"/>', 1)
        os.utime(self.path, (1, 1))
        with self.assertRaises(ValueError):
            report.collect(self.directory.name, 2, ["Matrix"])

    def test_class_setup_failure_is_recorded_not_lost(self):
        self.write('<testcase classname="synthetic.Matrix" name=""><error/></testcase>', 1)
        result = report.collect(self.directory.name, 0, ["Matrix"])
        self.assertEqual(result["summary"], {"PASS": 0, "FAIL": 1, "SKIPPED": 0})
        self.assertTrue(result["cases"][0]["lifecycleFailure"])

    def test_rejects_duplicate_or_missing_matrix(self):
        case = '<testcase classname="synthetic.Matrix" name="case1"/>'
        self.write(case + case, 2)
        with self.assertRaises(ValueError):
            report.collect(self.directory.name, 0, ["Matrix"])
        self.write(case, 1)
        with self.assertRaises(ValueError):
            report.collect(self.directory.name, 0, ["Missing"])

    def test_rejects_incomplete_or_malformed_xml(self):
        self.write('<testcase classname="synthetic.Matrix" name="case1"/>', 2)
        with self.assertRaises(ValueError):
            report.collect(self.directory.name, 0, ["Matrix"])
        self.path.write_text('<testsuite>')
        with self.assertRaises(Exception):
            report.collect(self.directory.name, 0, ["Matrix"])


if __name__ == "__main__":
    unittest.main()
