import argparse
import hashlib
import json
from pathlib import Path
import platform
import sys
import xml.etree.ElementTree as ET


def collect(reports, since, matrix_classes):
    cases = []
    identifiers = set()
    files = []
    for path in sorted(Path(reports).glob("TEST-*.xml")):
        if path.stat().st_mtime < since:
            continue
        root = ET.parse(path).getroot()
        entries = root.findall("testcase")
        if int(root.get("tests", "0")) != len(entries):
            raise ValueError("Incomplete testcase report: " + path.name)
        files.append(path.name)
        for ordinal, entry in enumerate(entries):
            classname = entry.get("classname", "")
            name = entry.get("name", "")
            lifecycle_failure = not name and (entry.find("failure") is not None or entry.find("error") is not None)
            if lifecycle_failure:
                name = "<class-lifecycle-failure>[" + str(ordinal) + "]"
            identifier = classname + "#" + name
            if not classname or not name or identifier in identifiers:
                raise ValueError("Missing or duplicate testcase identity")
            identifiers.add(identifier)
            status = "PASS"
            if entry.find("failure") is not None or entry.find("error") is not None:
                status = "FAIL"
            elif entry.find("skipped") is not None:
                status = "SKIPPED"
            cases.append({"id": identifier, "status": status,
                          "lifecycleFailure": lifecycle_failure,
                          "seconds": float(entry.get("time", "0")),
                          "matrix": classname.rsplit(".", 1)[-1] in matrix_classes})
    if not files:
        raise ValueError("No fresh test reports; old results cannot establish a pass")
    missing = set(matrix_classes) - {item["id"].split("#")[0].rsplit(".", 1)[-1] for item in cases}
    if missing:
        raise ValueError("Missing matrix reports: " + ", ".join(sorted(missing)))
    counts = {status: sum(case["status"] == status for case in cases)
              for status in ("PASS", "FAIL", "SKIPPED")}
    matrix_counts = {status: sum(case["matrix"] and case["status"] == status for case in cases)
                     for status in counts}
    return {"summary": counts, "matrixSummary": matrix_counts, "files": files, "cases": cases}


def source_digest(root):
    digest = hashlib.sha256()
    paths = []
    for relative in ("backend/src/main", "backend/src/test", "frontend/src", "desktop/src-tauri/src", "scripts"):
        for path in sorted((root / relative).rglob("*")):
            if path.is_file() and not path.is_symlink() and "__pycache__" not in path.parts:
                paths.append(path)
    paths.extend(root / name for name in ("backend/pom.xml", "frontend/package.json", "frontend/package-lock.json",
                                         "desktop/package.json", "desktop/package-lock.json", "desktop/src-tauri/Cargo.toml",
                                         "desktop/src-tauri/Cargo.lock", "desktop/src-tauri/tauri.conf.json"))
    for path in sorted(paths):
        digest.update(str(path.relative_to(root)).encode())
        digest.update(b"\0")
        digest.update(path.read_bytes())
        digest.update(b"\0")
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--reports", default="backend/target/surefire-reports")
    parser.add_argument("--since", type=float, required=True)
    parser.add_argument("--matrix-class", action="append", required=True)
    parser.add_argument("--minimum", type=int, default=5000)
    parser.add_argument("--expected-source-sha256", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    current = source_digest(root)
    if current != args.expected_source_sha256:
        raise ValueError("Source changed during verification; rerun against the final tree")
    report = collect(args.reports, args.since, args.matrix_class)
    report.update({"schemaVersion": 1, "sourceSha256": current,
                   "startedAfterEpoch": args.since, "hostArchitecture": platform.machine(),
                   "scope": "Synthetic JVM component/integration cases and parameter variants, not physical Mac/company SSO acceptance",
                   "companySsoVerified": False, "armRuntimeVerified": False,
                   "minimumMatrixCases": args.minimum})
    report["gatePassed"] = (report["summary"]["FAIL"] == 0
                            and report["matrixSummary"]["PASS"] >= args.minimum
                            and report["matrixSummary"]["SKIPPED"] == 0)
    Path(args.output).write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: report[key] for key in ("summary", "matrixSummary", "gatePassed")}, ensure_ascii=False))
    return 0 if report["gatePassed"] else 1


if __name__ == "__main__":
    sys.exit(main())
