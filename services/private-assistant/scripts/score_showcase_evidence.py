"""Score declared same-citation evidence groups from a synthetic showcase report."""
import argparse
import json
from pathlib import Path


def score(report, rules):
    if report.get("synthetic") is not True or report.get("cloudCallDelta") != 0:
        raise ValueError("Expected a zero-cloud synthetic showcase report")
    cases = {case["id"]: case for case in report["cases"]}
    if not rules or not rules.keys() <= cases.keys():
        raise ValueError("The report is missing labeled cases")
    results = {}
    for case_id, groups in rules.items():
        citations = cases[case_id]["citations"]
        ranks = [
            next((i for i, row in enumerate(citations, start=1)
                  if row["name"].startswith(group["source"]) and
                  all(token in row["text"] for token in group["tokens"])), None)
            for group in groups
        ]
        results[case_id] = {"ranks": ranks, "allReturned": all(ranks),
                            "firstFour": all(rank is not None and rank <= 4 for rank in ranks)}
    return {"total": len(results),
            "completeReturned": sum(row["allReturned"] for row in results.values()),
            "completeFirstFour": sum(row["firstFour"] for row in results.values()),
            "cases": results}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path)
    args = parser.parse_args()
    rules = Path(__file__).resolve().parents[1] / "examples/lamp-showcase/evidence-checks.json"
    print(json.dumps(score(json.loads(args.report.read_text(encoding="utf-8")),
                           json.loads(rules.read_text(encoding="utf-8"))), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
